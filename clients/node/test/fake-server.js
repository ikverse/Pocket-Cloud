"use strict";
// An in-memory Pocket Cloud for the library tests: docs/app-api.md section 11. A real HTTP server on
// 127.0.0.1 with a random port. No S3, no Range on pub, no retention runs.
//
//   const fake = await startFake();   // fake.endpoint, fake.secret; await fake.close()

const http = require("http");
const crypto = require("crypto");

const ALL_FEATURES = ["quota", "conflicts", "mirror-report", "share", "push", "files", "move", "changes", "publish", "retention", "notify"];
const SECRET = "fake-enroll-secret";
const GiB = 1073741824;
const MAX_PUT = 2147483648;
const SKEW = 15 * 60;
const EMPTY_SHA = crypto.createHash("sha256").digest("hex");

const sha256hex = (b) => crypto.createHash("sha256").update(b).digest("hex");
const md5etag = (b) => `"${crypto.createHash("md5").update(b).digest("hex")}"`;

/** verify_pocket from server/pocket/sigv4.py. Returns "" when good, else the error code. */
function verifyPocket(method, rawPath, rawQuery, headers, bodySha, secret, now) {
  const date = /^-?\d+$/.test(headers["x-pocket-date"] || "") ? Number(headers["x-pocket-date"]) : NaN;
  if (!Number.isFinite(date)) return "MissingSignature";
  if (Math.abs(now - date) > SKEW) return "RequestTimeTooSkewed";
  const text = [method.toUpperCase(), rawPath, rawQuery || "", String(date), bodySha].join("\n");
  const want = Buffer.from(crypto.createHmac("sha256", secret).update(text, "utf8").digest("hex"));
  const got = Buffer.from(String(headers["x-pocket-sign"] || ""));
  return got.length === want.length && crypto.timingSafeEqual(got, want) ? "" : "SignatureDoesNotMatch";
}

class Refuse extends Error {
  constructor(status, code, message, extra = {}, headers = {}) {
    super(message);
    Object.assign(this, { status, code, extra, headers });
  }
}

function checkKey(key) {
  const bytes = Buffer.byteLength(key, "utf8");
  if (bytes < 1 || bytes > 1024 || key.startsWith("/") || key.endsWith("/") || key.includes("\0")) return false;
  return key.split("/").every((s) => s && s !== "." && s !== "..");
}

const decodeSeg = (s) => {
  try {
    return decodeURIComponent(s);
  } catch {
    throw new Refuse(400, "BadKey", "The key is not percent-encoded correctly.");
  }
};

function parseQuery(raw) {
  const out = {};
  for (const part of raw ? raw.split("&") : []) {
    const i = part.indexOf("=");
    const k = decodeURIComponent(i < 0 ? part : part.slice(0, i));
    out[k] = i < 0 ? "" : decodeURIComponent(part.slice(i + 1).replace(/\+/g, "%20"));
  }
  return out;
}

const httpDate = (t) => new Date(t * 1000).toUTCString();

class Fake {
  constructor() {
    this.reset();
  }

  reset() {
    this.clock = null;
    this.features = [...ALL_FEATURES];
    this.apps = new Map(); // app -> switches
    this.keys = new Map(); // access key -> {app, device, name, secret}
    this.objects = new Map(); // app \0 bucket \0 owner \0 key -> {data, contentType, etag, modified}
    this.changes = [];
    this.seq = 0;
    this.expiredUpTo = 0;
    this.waiters = new Set();
    this.notifications = [];
    this.sentTimes = new Map(); // app -> [time]
    this.fails = [];
    this.breaks = [];
    this.slowMs = 0;
    this.saveMs = 0; // /_fake/save: how long a PUT takes to "save" after its whole body is in, before answering
    this.moved = 0;
    this.requests = [];
    this.enrolls = 0;
  }

  now() {
    return this.clock ?? Date.now() / 1000;
  }

  appCfg(app) {
    if (!this.apps.has(app)) {
      this.apps.set(app, { publish: false, notify: false, read_only: false, allocation: GiB, retention: [], push: null });
    }
    return this.apps.get(app);
  }

  // ---- storage ----
  oid(app, bucket, owner, key) {
    return [app, bucket, bucket === "device" ? owner : "", key].join("\0");
  }

  used(app) {
    let n = 0;
    for (const [id, o] of this.objects) if (id.startsWith(app + "\0")) n += o.data.length;
    return n;
  }

  usedDevice(app, dev) {
    let n = 0;
    const p = [app, "device", dev, ""].join("\0");
    for (const [id, o] of this.objects) if (id.startsWith(p)) n += o.data.length;
    return n;
  }

  record(rec) {
    this.seq += 1 + (this.seq % 3 === 0 ? 1 : 0); // gaps are normal
    this.changes.push({ seq: this.seq, time: this.now(), source: "app", ...rec });
    for (const w of [...this.waiters]) w();
  }

  visible(ctx) {
    return (c) => c.app === ctx.app && (c.bucket !== "device" || c.owner === ctx.device);
  }

  publicChange(c) {
    const out = { seq: c.seq, bucket: c.bucket, key: c.key, kind: c.kind };
    if (c.kind === "move") out.from = c.from;
    Object.assign(out, { size: c.size, etag: c.etag, time: c.time, device: c.device, source: c.source });
    return out;
  }

  // ---- HTTP ----
  async handle(req, res) {
    const url = req.url;
    const qi = url.indexOf("?");
    const rawPath = qi < 0 ? url : url.slice(0, qi);
    const rawQuery = qi < 0 ? "" : url.slice(qi + 1);
    const signer = this.keys.get(req.headers["x-pocket-key"] || "");
    // app: which app's key signed it (null when unsigned), so a test can leave out other tests' late requests
    const log = { method: req.method, path: rawPath, query: rawQuery, status: 200, code: null, app: signer ? signer.app : null };
    const send = (status, body, headers = {}) => {
      log.status = status;
      if (body && body.error && status >= 400) log.code = body.error;
      if (!rawPath.startsWith("/_fake/")) this.requests.push(log);
      if (req.method === "HEAD" || status === 304) {
        if (body && body.error) headers["x-pocket-error-code"] = body.error;
        res.writeHead(status, headers);
        return res.end();
      }
      if (Buffer.isBuffer(body)) {
        res.writeHead(status, { ...headers, "content-length": body.length });
        // /_fake/break: send half the body, then drop the connection ("cut") or go quiet ("stall")
        const brk = this.breaks.find((b) => b.times > 0 && `${req.method} ${rawPath}`.startsWith(b.route));
        if (brk && body.length > 1) {
          brk.times--;
          res.write(body.subarray(0, body.length >> 1));
          if (brk.mode === "cut") setTimeout(() => res.socket && res.socket.destroy(), 20);
          return;
        }
        return res.end(body);
      }
      const text = Buffer.from(JSON.stringify(body));
      res.writeHead(status, { "content-type": "application/json", "content-length": text.length, ...headers });
      res.end(text);
    };
    try {
      const out = await this.route(req, rawPath, rawQuery);
      send(out.status || 200, out.body, out.headers || {});
    } catch (e) {
      if (!(e instanceof Refuse)) {
        console.error(e);
        e = new Refuse(500, "InternalError", String(e && e.message));
      }
      // a refused request may still have its body on the way: take it, then answer
      req.resume();
      if (!req.complete) await new Promise((r) => req.on("end", r).on("error", r).on("close", r));
      send(e.status, { error: e.code, message: e.message, ...e.extra }, e.headers);
    }
  }

  readBody(req, limit = 8 * 1024 * 1024) {
    return new Promise((resolve, reject) => {
      const parts = [];
      let n = 0;
      req.on("data", (d) => {
        if (this.slowMs) {
          // /_fake/slow: a slow link, taking each chunk after a pause
          req.pause();
          setTimeout(() => req.resume(), this.slowMs);
        }
        n += d.length;
        if (n <= limit) parts.push(d);
      });
      req.on("end", () => (n > limit ? reject(new Refuse(400, "EntityTooLarge", "Body too large.")) : resolve(Buffer.concat(parts))));
      req.on("error", reject);
    });
  }

  async json(req) {
    const b = await this.readBody(req);
    try {
      const v = JSON.parse(b.toString("utf8") || "{}");
      if (!v || typeof v !== "object" || Array.isArray(v)) throw new Error();
      return v;
    } catch {
      throw new Refuse(400, "BadRequest", "The body is not a JSON object.");
    }
  }

  async route(req, rawPath, rawQuery) {
    const m = req.method;
    if (rawPath.startsWith("/_fake/")) return this.control(req, rawPath, rawQuery);
    if (rawPath === "/_pocket/health" && m === "GET") {
      return { body: { ok: true, version: "0.3.0-fake", features: this.features } };
    }
    if (rawPath === "/_pocket/enroll" && m === "POST") return this.enroll(req);
    if (rawPath.startsWith("/_pocket/pub/")) return this.pub(req, rawPath);
    if (!rawPath.startsWith("/_pocket/")) throw new Refuse(404, "NotFound", "Not a Pocket Cloud route.");

    // ---- signed ----
    const fail = this.fails.find((f) => `${m} ${rawPath}`.startsWith(f.route) && f.times > 0);
    const headers = req.headers;
    const ak = headers["x-pocket-key"] || "";
    const dev = this.keys.get(ak);
    if (!dev) throw new Refuse(403, "InvalidAccessKeyId", "This key is not known to Pocket Cloud.");
    let bodySha;
    let body = null;
    if (m === "PUT") {
      bodySha = headers["x-pocket-content-sha256"];
      if (!bodySha) throw new Refuse(400, "BadRequest", "Uploads send x-pocket-content-sha256.");
    }
    const check = (sha) => {
      const why = verifyPocket(m, rawPath, rawQuery, headers, sha, dev.secret, this.now());
      if (why) throw new Refuse(403, why, "The request is not signed correctly for this key.");
    };
    if (m === "PUT") check(bodySha);
    else {
      body = await this.readBody(req);
      const sha = sha256hex(body);
      if (headers["x-pocket-content-sha256"] && headers["x-pocket-content-sha256"] !== sha) {
        throw new Refuse(400, "BadDigest", "The body does not match x-pocket-content-sha256.");
      }
      check(sha);
    }
    if (this.moved > 0) {
      this.moved--;
      throw new Refuse(403, "AccessDenied", "This key belongs to a device at another address. Enroll again from this device.", {}, {
        "x-pocket-error": "device-address-changed",
      });
    }
    if (fail) {
      fail.times--;
      const h = {};
      if (fail.status === 503 || fail.status === 429) h["retry-after"] = String(fail.retry_after ?? 1);
      throw new Refuse(fail.status, fail.code, `Failing on purpose (${fail.code}).`, {}, h);
    }
    const ctx = { app: dev.app, device: dev.device, name: dev.name, cfg: this.appCfg(dev.app) };
    const need = (f) => {
      if (!this.features.includes(f)) throw new Refuse(404, "NotFound", "Unknown Pocket Cloud endpoint.");
    };
    const q = parseQuery(rawQuery);

    if (rawPath.startsWith("/_pocket/files/")) {
      need("files");
      const segs = rawPath.slice("/_pocket/files/".length).split("/");
      const bucket = decodeSeg(segs.shift());
      const key = segs.map(decodeSeg).join("/");
      if (!checkKey(key)) throw new Refuse(400, "BadKey", "The key breaks the key rules.");
      if (bucket !== "shared" && bucket !== "device") throw new Refuse(404, "NoSuchBucket", "No such bucket for this app.");
      if (m === "GET" || m === "HEAD") return this.read(req, ctx, bucket, key);
      if (m === "PUT") return this.write(req, ctx, bucket, key, bodySha);
      if (m === "DELETE") return this.remove(req, ctx, bucket, key);
      throw new Refuse(405, "BadRequest", "Method not allowed.");
    }
    const r = `${m} ${rawPath}`;
    if (r === "GET /_pocket/app") return need("files"), { body: this.appInfo(ctx) };
    if (r === "GET /_pocket/list") return need("files"), this.list(ctx, q);
    if (r === "POST /_pocket/move") return need("move"), this.move(ctx, this.parse(body));
    if (r === "GET /_pocket/changes") return need("changes"), this.feed(ctx, q, req);
    if (r === "POST /_pocket/notify") return need("notify"), this.notify(ctx, this.parse(body));
    if (r === "GET /_pocket/quota") return need("quota"), { body: this.quota(ctx) };
    if (r === "POST /_pocket/share") return need("share"), this.share(ctx, this.parse(body));
    throw new Refuse(404, "NotFound", "Unknown Pocket Cloud endpoint.");
  }

  parse(buf) {
    try {
      const v = JSON.parse(buf.toString("utf8") || "{}");
      if (v && typeof v === "object" && !Array.isArray(v)) return v;
    } catch {}
    throw new Refuse(400, "BadRequest", "The body is not a JSON object.");
  }

  // ---- enroll ----
  async enroll(req) {
    const b = await this.json(req);
    if (b.secret !== SECRET) throw new Refuse(403, "BadSecret", "The enrollment secret is wrong.");
    if (!b.app || !b.device_id) throw new Refuse(400, "BadRequest", "app and device_id are needed.");
    for (const [k, v] of this.keys) if (v.app === b.app && v.device === b.device_id) this.keys.delete(k);
    const access = "AKFAKE" + String(crypto.randomInt(0, 1e10)).padStart(10, "0");
    const secret = crypto.randomBytes(20).toString("hex");
    this.keys.set(access, { app: b.app, device: b.device_id, name: b.device_name || b.device_id, secret });
    this.appCfg(b.app);
    this.enrolls++;
    return {
      body: {
        app: b.app,
        device_id: b.device_id,
        access_key: access,
        secret_key: secret,
        region: "us-east-1",
        endpoint: this.endpoint,
        endpoints: { tailscale: this.endpoint, lan: null },
        push: null,
      },
    };
  }

  appInfo(ctx) {
    const c = ctx.cfg;
    return {
      app: ctx.app,
      device_id: ctx.device,
      buckets: ["shared", "device"],
      features: this.features,
      publish: { enabled: !!c.publish, url: `${this.endpoint}/_pocket/pub/${ctx.app}/` },
      notify: { enabled: !!c.notify, topic: `pocket-${ctx.app}-notify`, url: c.push ? c.push.url : null },
      push: c.push ? { topic: c.push.topic || `pocket-${ctx.app}`, url: c.push.url } : null,
      retention: c.retention,
      changes: { keep_days: 30 },
      read_only: !!c.read_only,
    };
  }

  // ---- files ----
  fileHeaders(o) {
    return {
      "content-type": o.contentType || "application/octet-stream",
      etag: o.etag,
      "last-modified": httpDate(o.modified),
      "x-pocket-modified": String(o.modified),
      "accept-ranges": "bytes",
    };
  }

  serve(req, o) {
    const h = this.fileHeaders(o);
    const inm = req.headers["if-none-match"];
    if (inm && (inm === "*" || inm.split(",").map((s) => s.trim()).includes(o.etag))) {
      return { status: 304, body: null, headers: h };
    }
    const im = req.headers["if-match"];
    if (im && im !== "*" && !im.split(",").map((s) => s.trim()).includes(o.etag)) {
      throw new Refuse(412, "PreconditionFailed", "The file's ETag does not match if-match.");
    }
    const range = req.headers.range;
    if (range) {
      const mm = range.match(/^bytes=(\d*)-(\d*)$/);
      const size = o.data.length;
      let a;
      let b;
      if (mm && mm[1] !== "") {
        a = Number(mm[1]);
        b = mm[2] === "" ? size - 1 : Math.min(Number(mm[2]), size - 1);
      } else if (mm && mm[2] !== "") {
        a = Math.max(0, size - Number(mm[2]));
        b = size - 1;
      }
      if (a === undefined || a >= size || b < a) {
        throw new Refuse(416, "InvalidRange", "The range is outside the file.", {}, { "content-range": `bytes */${size}` });
      }
      return { status: 206, body: o.data.subarray(a, b + 1), headers: { ...h, "content-range": `bytes ${a}-${b}/${size}`, "content-length": b + 1 - a } };
    }
    return { status: 200, body: o.data, headers: { ...h, "content-length": o.data.length } };
  }

  read(req, ctx, bucket, key) {
    const o = this.objects.get(this.oid(ctx.app, bucket, ctx.device, key));
    if (!o) throw new Refuse(404, "NoSuchKey", "No file at that key.");
    return this.serve(req, o);
  }

  async write(req, ctx, bucket, key, bodySha) {
    const lenH = req.headers["content-length"];
    if (lenH == null || req.headers["transfer-encoding"]) throw new Refuse(400, "BadRequest", "Uploads need Content-Length.");
    const len = Number(lenH);
    if (len > MAX_PUT) throw new Refuse(400, "EntityTooLarge", "One file is at most 2 GiB here; use S3 multipart.");
    if (ctx.cfg.read_only) throw new Refuse(403, "ReadOnly", "Pocket Cloud is in read-only mode.");
    const id = this.oid(ctx.app, bucket, ctx.device, key);
    const old = this.objects.get(id);
    const im = req.headers["if-match"];
    const inm = req.headers["if-none-match"];
    if ((im && (!old || im !== old.etag)) || (inm === "*" && old)) {
      throw new Refuse(412, "PreconditionFailed", "The file changed: if-match / if-none-match did not hold.");
    }
    const grow = len - (old ? old.data.length : 0);
    if (this.used(ctx.app) + grow > ctx.cfg.allocation) {
      throw new Refuse(403, "QuotaExceeded", `${ctx.app} would go over its ${(ctx.cfg.allocation / GiB).toFixed(2)} GB allocation.`);
    }
    const data = await this.readBody(req, MAX_PUT);
    if (this.saveMs) await new Promise((r) => setTimeout(r, this.saveMs)); // like the phone saving a big file
    if (sha256hex(data) !== bodySha) throw new Refuse(400, "BadDigest", "The body does not match x-pocket-content-sha256.");
    const mod = req.headers["x-pocket-modified"];
    const modified = mod && Number.isFinite(Number(mod)) ? Number(mod) : this.now();
    const o = { data, contentType: req.headers["content-type"] || null, etag: md5etag(data), modified };
    this.objects.set(id, o);
    this.record({ app: ctx.app, bucket, owner: ctx.device, key, kind: "put", size: data.length, etag: o.etag, device: ctx.device });
    return { body: { bucket, key, size: data.length, etag: o.etag, modified, created: !old } };
  }

  remove(req, ctx, bucket, key) {
    if (ctx.cfg.read_only) throw new Refuse(403, "ReadOnly", "Pocket Cloud is in read-only mode.");
    const id = this.oid(ctx.app, bucket, ctx.device, key);
    const old = this.objects.get(id);
    if (!old) throw new Refuse(404, "NoSuchKey", "No file at that key.");
    const im = req.headers["if-match"];
    if (im && im !== old.etag) throw new Refuse(412, "PreconditionFailed", "The file's ETag does not match if-match.");
    this.objects.delete(id);
    this.record({ app: ctx.app, bucket, owner: ctx.device, key, kind: "delete", size: null, etag: null, device: ctx.device });
    return { body: { bucket, key, deleted: true } };
  }

  list(ctx, q) {
    const bucket = q.bucket;
    if (!bucket) throw new Refuse(400, "BadRequest", "bucket is needed.");
    if (bucket !== "shared" && bucket !== "device") throw new Refuse(404, "NoSuchBucket", "No such bucket for this app.");
    const prefix = q.prefix || "";
    const delim = q.delimiter;
    if (delim !== undefined && delim !== "/") throw new Refuse(400, "BadRequest", "delimiter is only '/'.");
    const limit = q.limit === undefined ? 1000 : Number(q.limit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 1000) throw new Refuse(400, "BadRequest", "limit is 1-1000.");
    let after = null;
    if (q.cursor) {
      let c;
      try {
        c = JSON.parse(Buffer.from(q.cursor, "base64url").toString("utf8"));
      } catch {}
      if (!c || c.b !== bucket || c.p !== prefix || c.d !== (delim || null)) {
        throw new Refuse(400, "BadRequest", "The cursor is for another listing.");
      }
      after = c.a;
    }
    const head = this.oid(ctx.app, bucket, ctx.device, "");
    const items = new Map();
    for (const [id, o] of this.objects) {
      if (!id.startsWith(head)) continue;
      const key = id.slice(head.length);
      if (!key.startsWith(prefix)) continue;
      const rest = key.slice(prefix.length);
      const i = delim ? rest.indexOf("/") : -1;
      if (i >= 0) items.set(prefix + rest.slice(0, i + 1), null);
      else items.set(key, o);
    }
    const sorted = [...items.keys()].sort((a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b)));
    const start = after === null ? 0 : sorted.findIndex((k) => Buffer.compare(Buffer.from(k), Buffer.from(after)) > 0);
    const page = start < 0 ? [] : sorted.slice(start, start + limit);
    const more = start >= 0 && start + limit < sorted.length;
    const out = { bucket, prefix, files: [] };
    if (delim) out.folders = [];
    for (const k of page) {
      const o = items.get(k);
      if (o) out.files.push({ key: k, size: o.data.length, etag: o.etag, modified: o.modified });
      else out.folders.push(k);
    }
    out.cursor = more
      ? Buffer.from(JSON.stringify({ b: bucket, p: prefix, d: delim || null, a: page[page.length - 1] })).toString("base64url")
      : null;
    return { body: out };
  }

  move(ctx, b) {
    const ok = (x) => x && typeof x === "object" && typeof x.bucket === "string" && typeof x.key === "string";
    if (!ok(b.from) || !ok(b.to)) throw new Refuse(400, "BadRequest", "from and to are {bucket, key}.");
    for (const x of [b.from, b.to]) {
      if (!checkKey(x.key)) throw new Refuse(400, "BadKey", "The key breaks the key rules.");
      if (x.bucket !== "shared" && x.bucket !== "device") throw new Refuse(404, "NoSuchBucket", "No such bucket for this app.");
    }
    if (ctx.cfg.read_only) throw new Refuse(403, "ReadOnly", "Pocket Cloud is in read-only mode.");
    const fromId = this.oid(ctx.app, b.from.bucket, ctx.device, b.from.key);
    const toId = this.oid(ctx.app, b.to.bucket, ctx.device, b.to.key);
    const src = this.objects.get(fromId);
    if (!src) throw new Refuse(404, "NoSuchKey", "No file at that key.");
    if (b.if_match && b.if_match !== src.etag) throw new Refuse(412, "PreconditionFailed", "The source's ETag does not match.");
    if (this.objects.has(toId) && fromId !== toId && !b.overwrite) throw new Refuse(409, "KeyExists", "The target exists.");
    this.objects.delete(fromId);
    this.objects.set(toId, src);
    this.record({
      app: ctx.app,
      bucket: b.to.bucket,
      owner: ctx.device,
      key: b.to.key,
      kind: "move",
      from: { bucket: b.from.bucket, key: b.from.key },
      size: src.data.length,
      etag: src.etag,
      device: ctx.device,
    });
    return { body: { from: b.from, to: b.to, size: src.data.length, etag: src.etag } };
  }

  // ---- change feed ----
  async feed(ctx, q, req) {
    const limit = q.limit === undefined ? 500 : Number(q.limit);
    const wait = q.wait === undefined ? 0 : Number(q.wait);
    if (!Number.isInteger(limit) || limit < 1 || limit > 1000) throw new Refuse(400, "BadRequest", "limit is 1-1000.");
    if (!Number.isInteger(wait) || wait < 0 || wait > 60) throw new Refuse(400, "BadRequest", "wait is 0-60.");
    if (q.since === undefined) return { body: { changes: [], cursor: String(this.seq), more: false } };
    const since = /^\d+$/.test(q.since) ? Number(q.since) : NaN;
    if (!Number.isFinite(since) || since > this.seq || since < this.expiredUpTo) {
      throw new Refuse(410, "CursorExpired", "That cursor is older than the changes kept.", { cursor: String(this.seq) });
    }
    const pick = () => this.changes.filter((c) => c.seq > since).filter(this.visible(ctx));
    let found = pick();
    if (!found.length && wait > 0) {
      await new Promise((resolve) => {
        const done = () => {
          clearTimeout(t);
          this.waiters.delete(check);
          req.socket.off("close", done);
          resolve();
        };
        const check = () => {
          if (pick().length) done();
        };
        const t = setTimeout(done, wait * 1000);
        this.waiters.add(check);
        req.socket.on("close", done); // the client gave up waiting
      });
      found = pick();
    }
    const page = found.slice(0, limit);
    const more = found.length > limit;
    const cursor = more ? String(page[page.length - 1].seq) : String(this.seq);
    return { body: { changes: page.map((c) => this.publicChange(c)), cursor, more } };
  }

  // ---- publish folder ----
  pub(req, rawPath) {
    if (req.method !== "GET" && req.method !== "HEAD") throw new Refuse(405, "BadRequest", "The publish folder is read-only.");
    const segs = rawPath.slice("/_pocket/pub/".length).split("/");
    const app = decodeSeg(segs.shift());
    const key = segs.map(decodeSeg).join("/");
    const cfg = this.apps.get(app);
    const nf = new Refuse(404, "NotFound", "Not found.");
    if (!cfg || !cfg.publish || !key || !checkKey(key)) throw nf;
    const o = this.objects.get(this.oid(app, "shared", "", "releases/" + key));
    if (!o) throw nf;
    const out = this.serve(req, o);
    const name = key.split("/").pop();
    const top = !key.includes("/");
    out.headers["cache-control"] = top && (name === "latest.json" || /^latest.*\.yml$/.test(name)) ? "no-cache" : "max-age=86400";
    return out;
  }

  // ---- notify ----
  notify(ctx, b) {
    if (!ctx.cfg.notify) throw new Refuse(403, "FeatureOff", "Notifications are switched off for this app.");
    const bad = (m) => {
      throw new Refuse(400, "BadRequest", m);
    };
    if (typeof b.message !== "string" || b.message.length < 1 || b.message.length > 4096) bad("message is 1-4096 characters.");
    if (b.title != null && (typeof b.title !== "string" || b.title.length > 200)) bad("title is up to 200 characters.");
    if (b.tags != null && (!Array.isArray(b.tags) || b.tags.length > 10 || b.tags.some((t) => typeof t !== "string" || !/^[a-z0-9_-]{1,40}$/.test(t)))) {
      bad("tags are up to 10 of a-z 0-9 _ -, up to 40 characters.");
    }
    if (b.priority != null && !(Number.isInteger(b.priority) && b.priority >= 1 && b.priority <= 5)) bad("priority is 1-5.");
    if (b.click != null && (typeof b.click !== "string" || b.click.length > 2000)) bad("click is up to 2000 characters.");
    const now = this.now();
    const times = (this.sentTimes.get(ctx.app) || []).filter((t) => t > now - 86400);
    const minute = times.filter((t) => t > now - 60);
    if (minute.length >= 10 || times.length >= 200) {
      const wait = minute.length >= 10 ? Math.ceil(minute[0] + 60 - now) : Math.ceil(times[0] + 86400 - now);
      throw new Refuse(429, "RateLimited", "Too many notifications.", {}, { "retry-after": String(Math.max(1, wait)) });
    }
    times.push(now);
    this.sentTimes.set(ctx.app, times);
    this.notifications.push({
      app: ctx.app,
      device: ctx.device,
      message: b.message,
      title: b.title ?? null,
      tags: b.tags ?? [],
      priority: b.priority ?? 3,
      click: b.click ?? null,
      time: now,
    });
    const topic = `pocket-${ctx.app}-notify`;
    return { body: { ok: true, id: crypto.randomBytes(6).toString("hex"), topic } };
  }

  quota(ctx) {
    const alloc = ctx.cfg.allocation;
    const used = this.used(ctx.app);
    return {
      app: ctx.app,
      device_id: ctx.device,
      allocation: alloc,
      used_app: used,
      used_device: this.usedDevice(ctx.app, ctx.device),
      device_cap: null,
      reserve: 0,
      held_for_others: 0,
      free: Math.max(0, alloc - used),
      free_shared: Math.max(0, alloc - used),
      read_only: !!ctx.cfg.read_only,
    };
  }

  share(ctx, b) {
    const bucket = String(b.bucket || "");
    if (bucket !== "shared" && bucket !== "device") throw new Refuse(400, "BadRequest", "bucket is 'shared' or 'device'.");
    if (!this.objects.has(this.oid(ctx.app, bucket, ctx.device, String(b.key || "")))) {
      throw new Refuse(404, "NoSuchKey", "There is no such file to share.");
    }
    const expires = Number(b.expires_s || 86400);
    if (!Number.isInteger(expires) || expires < 60 || expires > 30 * 86400) throw new Refuse(400, "BadRequest", "expires_s is 60 s to 30 days.");
    const token = crypto.randomBytes(12).toString("base64url");
    const now = this.now();
    return {
      body: {
        token,
        url: `${this.endpoint}/s/${token}`,
        public: false,
        bucket,
        key: b.key,
        created: now,
        expires: now + expires,
        downloads: 0,
        max_downloads: b.max_downloads ?? null,
        has_password: !!b.password,
      },
    };
  }

  // ---- /_fake/ controls ----
  async control(req, rawPath, rawQuery) {
    const r = `${req.method} ${rawPath}`;
    const b = req.method === "POST" ? await this.json(req) : {};
    const q = parseQuery(rawQuery);
    if (r === "POST /_fake/reset") return this.reset(), { body: { ok: true } };
    if (r === "POST /_fake/clock") return (this.clock = b.now ?? null), { body: { ok: true } };
    if (r === "POST /_fake/features") return (this.features = [...b.features]), { body: { ok: true } };
    if (r === "POST /_fake/app") {
      if (!q.app) throw new Refuse(400, "BadRequest", "?app= is needed.");
      Object.assign(this.appCfg(q.app), b);
      return { body: { ok: true } };
    }
    if (r === "POST /_fake/fail") return this.fails.push({ times: 1, ...b }), { body: { ok: true } };
    // not in the contract's table: this library's own tests of timeouts and broken answers
    if (r === "POST /_fake/break") return this.breaks.push({ times: 1, mode: "cut", ...b }), { body: { ok: true } };
    if (r === "POST /_fake/slow") return (this.slowMs = b.ms || 0), { body: { ok: true } };
    if (r === "POST /_fake/save") return (this.saveMs = b.ms || 0), { body: { ok: true } };
    if (r === "POST /_fake/moved") return (this.moved = b.times ?? 1), { body: { ok: true } };
    if (r === "POST /_fake/expire-changes") {
      const before = Number(b.before);
      this.changes = this.changes.filter((c) => c.seq > before);
      this.expiredUpTo = Math.max(this.expiredUpTo, before); // cursors before it are gone, it still works
      return { body: { ok: true } };
    }
    if (r === "GET /_fake/notifications") return { body: { sent: this.notifications } };
    if (r === "GET /_fake/requests") return { body: { requests: this.requests } };
    throw new Refuse(404, "NotFound", "Unknown fake control.");
  }
}

/** Start a fake on 127.0.0.1 with a random port. */
function startFake() {
  const fake = new Fake();
  const server = http.createServer((req, res) => fake.handle(req, res));
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      fake.endpoint = `http://127.0.0.1:${server.address().port}`;
      fake.secret = SECRET;
      fake.server = server;
      fake.close = () =>
        new Promise((r) => {
          server.closeAllConnections();
          server.close(r);
        });
      resolve(fake);
    });
  });
}

module.exports = { startFake, Fake, verifyPocket, ALL_FEATURES, SECRET, EMPTY_SHA };
