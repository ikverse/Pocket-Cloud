"use strict";
/**
 * Pocket Cloud for Node apps.
 *
 *   const pocket = require("pocket-cloud");
 *   const s3 = await pocket.connect("egx");            // a ready S3Client (AWS SDK v3)
 *   await s3.send(new PutObjectCommand({ Bucket: "shared", Key: "prices/today.json", Body: data }));
 *
 * The first time an app connects from a machine, this enrolls it with the server and saves the device's key.
 * Every app has two buckets: "shared" (all its devices) and "device" (this device only).
 *
 * Machine setup, once per machine: <home>/.pocket/config with
 *
 *   [pocket]
 *   endpoint = http://pocket:9000
 *   enroll_secret = ...
 *
 * or the environment variables POCKET_ENDPOINT and POCKET_ENROLL_SECRET (for CI). POCKET_DEVICE_ID and
 * POCKET_DEVICE_NAME optionally fix the device's identity, e.g. POCKET_DEVICE_ID=github-actions.
 * The files are shared with the Python and Kotlin libraries, so all apps on a machine are one device.
 */

const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { S3Client } = require("@aws-sdk/client-s3");

const DEVICE_ID = /^[A-Za-z0-9-]{8,64}$/;

class PocketError extends Error {}

function homeDir(home) {
  return home || process.env.POCKET_HOME || path.join(os.homedir(), ".pocket");
}

function writePrivate(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = path.join(path.dirname(file), `.tmp-${crypto.randomBytes(6).toString("hex")}`);
  fs.writeFileSync(tmp, text, { mode: 0o600 });
  fs.renameSync(tmp, file);
}

function readIni(file) {
  let text;
  try {
    text = fs.readFileSync(file, "utf8");
  } catch {
    return {};
  }
  const out = {};
  let section = null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#") || line.startsWith(";")) continue;
    const m = line.match(/^\[(.+)\]$/);
    if (m) {
      section = m[1].trim();
      continue;
    }
    const eq = line.search(/[=:]/);
    if (section === "pocket" && eq > 0) out[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
  }
  return out;
}

function machineConfig(home, endpoint, enrollSecret) {
  endpoint = endpoint || process.env.POCKET_ENDPOINT;
  enrollSecret = enrollSecret || process.env.POCKET_ENROLL_SECRET;
  if (!endpoint || !enrollSecret) {
    const ini = readIni(path.join(home, "config"));
    endpoint = endpoint || ini.endpoint;
    enrollSecret = enrollSecret || ini.enroll_secret;
  }
  if (!endpoint || !enrollSecret) {
    throw new PocketError(
      "This machine is not set up for Pocket Cloud. Save the machine setup file from the control panel's " +
        `Settings page as ${path.join(home, "config")}, or set POCKET_ENDPOINT and POCKET_ENROLL_SECRET.`
    );
  }
  return { endpoint: endpoint.replace(/\/+$/, ""), enrollSecret };
}

function deviceIdFor(home, given) {
  given = given || process.env.POCKET_DEVICE_ID;
  if (given) {
    if (!DEVICE_ID.test(given)) throw new PocketError("A device id uses 8-64 letters, digits and dashes.");
    return given;
  }
  const file = path.join(home, "device_id");
  try {
    const existing = fs.readFileSync(file, "utf8").trim();
    if (DEVICE_ID.test(existing)) return existing;
  } catch {}
  const id = "dev-" + crypto.randomBytes(12).toString("hex");
  writePrivate(file, id + "\n");
  return id;
}

class Device {
  constructor(app, opts) {
    this.app = app;
    this.home = homeDir(opts.home);
    Object.assign(this, machineConfig(this.home, opts.endpoint, opts.enrollSecret));
    this.deviceId = deviceIdFor(this.home, opts.deviceId);
    this.deviceName = opts.deviceName || process.env.POCKET_DEVICE_NAME || os.hostname();
    this.file = path.join(this.home, "apps", `${app}.json`);
    this.creds = this.load();
    this.enrolling = null;
  }

  load() {
    try {
      const saved = JSON.parse(fs.readFileSync(this.file, "utf8"));
      if (saved.endpoint === this.endpoint && saved.device_id === this.deviceId) return saved;
    } catch {}
    return null;
  }

  /** Enroll (again); concurrent callers share one enrollment. */
  enroll() {
    if (!this.enrolling) {
      this.enrolling = this._enroll().finally(() => {
        this.enrolling = null;
      });
    }
    return this.enrolling;
  }

  async _enroll() {
    let res;
    try {
      res = await fetch(`${this.endpoint}/_pocket/enroll`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          secret: this.enrollSecret,
          app: this.app,
          device_id: this.deviceId,
          device_name: this.deviceName,
        }),
      });
    } catch (e) {
      throw new PocketError(`Pocket Cloud can't be reached at ${this.endpoint}: ${e.cause?.code || e.message}`);
    }
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new PocketError(`Pocket Cloud refused to enroll ${this.app}: ${body.message || res.status}`);
    const creds = {
      endpoint: this.endpoint,
      device_id: this.deviceId,
      access_key: body.access_key,
      secret_key: body.secret_key,
      region: body.region || "us-east-1",
    };
    writePrivate(this.file, JSON.stringify(creds, null, 2));
    this.creds = creds;
    return creds;
  }
}

/**
 * Connect app to Pocket Cloud and return a ready S3Client for its "shared" and "device" buckets.
 * @param {string} app
 * @param {{endpoint?: string, enrollSecret?: string, deviceId?: string, deviceName?: string, home?: string,
 *          clientConfig?: object}} [opts]
 */
async function connect(app, opts = {}) {
  const dev = new Device(app, opts);
  if (!dev.creds) await dev.enroll();

  const client = new S3Client({
    endpoint: dev.endpoint,
    region: dev.creds.region || "us-east-1",
    forcePathStyle: true,
    maxAttempts: 5,
    requestChecksumCalculation: "WHEN_REQUIRED",
    responseChecksumValidation: "WHEN_REQUIRED",
    // Read on every request, so a new key from re-enrollment is used straight away.
    credentials: async () => ({
      accessKeyId: dev.creds.access_key,
      secretAccessKey: dev.creds.secret_key,
      expiration: new Date(Date.now() + 1000),
    }),
    ...(opts.clientConfig || {}),
  });

  // The device moved to a new address or its key was replaced: enroll again and retry once.
  client.middlewareStack.add(
    (next) => async (args) => {
      try {
        return await next(args);
      } catch (err) {
        const status = err.$metadata?.httpStatusCode;
        const moved = err.$response?.headers?.["x-pocket-error"] === "device-address-changed";
        if (status === 403 && (moved || err.name === "InvalidAccessKeyId")) {
          await dev.enroll();
          return next(args);
        }
        throw err;
      }
    },
    { step: "initialize", name: "pocketReenroll", priority: "high" }
  );
  return client;
}

module.exports = { connect, PocketError };
