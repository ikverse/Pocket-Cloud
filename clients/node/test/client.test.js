"use strict";
// The Node library against the real Pocket Cloud server (started by server/tests/serve_for_tests.py).

const { test, before, after } = require("node:test");
const assert = require("node:assert");
const { spawn } = require("child_process");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const readline = require("readline");
const { PutObjectCommand, GetObjectCommand, ListObjectsV2Command } = require("@aws-sdk/client-s3");
const { SignatureV4 } = require("@smithy/signature-v4");
const pocket = require("..");

const ROOT = path.resolve(__dirname, "..", "..", "..");
let server, lines, info, home;

function command(cmd) {
  return new Promise((resolve) => {
    lines.once("line", (l) => resolve(JSON.parse(l)));
    server.stdin.write(cmd + "\n");
  });
}

before(async () => {
  server = spawn("python", [path.join(ROOT, "server", "tests", "serve_for_tests.py")], { stdio: ["pipe", "pipe", "inherit"] });
  lines = readline.createInterface({ input: server.stdout });
  info = await new Promise((resolve) => lines.once("line", (l) => resolve(JSON.parse(l))));
  home = fs.mkdtempSync(path.join(os.tmpdir(), "pocket-node-"));
  fs.writeFileSync(path.join(home, "config"), `[pocket]\nendpoint = ${info.endpoint}\nenroll_secret = ${info.secret}\n`);
});

after(async () => {
  server.stdin.write("quit\n");
  await new Promise((resolve) => server.on("exit", resolve));
  fs.rmSync(home, { recursive: true, force: true });
});

const text = async (out) => Buffer.from(await out.Body.transformToByteArray()).toString();

test("first connect enrolls, and both buckets work", async () => {
  const s3 = await pocket.connect("node-first", { home, deviceName: "Node test" });
  await s3.send(new PutObjectCommand({ Bucket: "shared", Key: "hello.txt", Body: "hello" }));
  await s3.send(new PutObjectCommand({ Bucket: "device", Key: "mine.txt", Body: "private" }));
  assert.strictEqual(await text(await s3.send(new GetObjectCommand({ Bucket: "shared", Key: "hello.txt" }))), "hello");
  assert.strictEqual(await text(await s3.send(new GetObjectCommand({ Bucket: "device", Key: "mine.txt" }))), "private");
  const id = fs.readFileSync(path.join(home, "device_id"), "utf8").trim();
  const { devices } = await command("devices node-first");
  assert.deepStrictEqual(devices.map((d) => [d.uid, d.name]), [[id, "Node test"]]);
});

test("later connects reuse the saved key", async () => {
  for (let i = 0; i < 3; i++) {
    const s3 = await pocket.connect("node-reuse", { home });
    await s3.send(new ListObjectsV2Command({ Bucket: "shared" }));
  }
  assert.strictEqual((await command("enrolls node-reuse")).count, 1);
});

test("recovers when the device address changes", async () => {
  const s3 = await pocket.connect("node-moved", { home });
  await s3.send(new PutObjectCommand({ Bucket: "shared", Key: "a", Body: "1" }));
  await command("move node-moved");
  assert.strictEqual(await text(await s3.send(new GetObjectCommand({ Bucket: "shared", Key: "a" }))), "1");
  const { devices } = await command("devices node-moved");
  assert.strictEqual(devices[0].ip, "127.0.0.1");
  assert.strictEqual((await command("enrolls node-moved")).count, 2);
});

test("recovers when the key is replaced", async () => {
  const s3 = await pocket.connect("node-rotated", { home });
  await s3.send(new PutObjectCommand({ Bucket: "shared", Key: "a", Body: "1" }));
  const { keys } = await command("rotate node-rotated");
  await s3.send(new PutObjectCommand({ Bucket: "shared", Key: "b", Body: "2" }));
  const saved = JSON.parse(fs.readFileSync(path.join(home, "apps", "node-rotated.json"), "utf8"));
  assert.strictEqual(saved.access_key, keys[0]);
  assert.strictEqual(await text(await s3.send(new GetObjectCommand({ Bucket: "shared", Key: "b" }))), "2");
});

test("temporary link", async () => {
  const s3 = await pocket.connect("node-link", { home });
  await s3.send(new PutObjectCommand({ Bucket: "shared", Key: "report.txt", Body: "by link" }));
  const saved = JSON.parse(fs.readFileSync(path.join(home, "apps", "node-link.json"), "utf8"));
  class Sha256 {
    constructor(secret) { this.h = secret ? crypto.createHmac("sha256", secret) : crypto.createHash("sha256"); }
    update(d) { this.h.update(d); }
    async digest() { return new Uint8Array(this.h.digest()); }
  }
  const signer = new SignatureV4({
    service: "s3", region: "us-east-1", sha256: Sha256, uriEscapePath: false,
    credentials: { accessKeyId: saved.access_key, secretAccessKey: saved.secret_key },
  });
  const url = new URL(info.endpoint + "/shared/report.txt");
  const signed = await signer.presign(
    // like @aws-sdk/s3-request-presigner: links never sign the body
    { method: "GET", protocol: "http:", hostname: url.hostname, port: Number(url.port), path: url.pathname,
      headers: { host: url.host, "x-amz-content-sha256": "UNSIGNED-PAYLOAD" }, query: {} },
    { expiresIn: 60 }
  );
  const qs = new URLSearchParams(signed.query).toString();
  const res = await fetch(`${info.endpoint}${signed.path}?${qs}`);
  assert.strictEqual(res.status, 200);
  assert.strictEqual(await res.text(), "by link");
});

test("environment variables for CI", async () => {
  const ciHome = fs.mkdtempSync(path.join(os.tmpdir(), "pocket-node-ci-"));
  Object.assign(process.env, {
    POCKET_ENDPOINT: info.endpoint, POCKET_ENROLL_SECRET: info.secret,
    POCKET_DEVICE_ID: "github-actions", POCKET_DEVICE_NAME: "GitHub Actions", POCKET_HOME: ciHome,
  });
  try {
    const s3 = await pocket.connect("node-ci");
    await s3.send(new PutObjectCommand({ Bucket: "shared", Key: "build.txt", Body: "ok" }));
    const { devices } = await command("devices node-ci");
    assert.deepStrictEqual(devices.map((d) => [d.uid, d.name]), [["github-actions", "GitHub Actions"]]);
  } finally {
    for (const k of ["POCKET_ENDPOINT", "POCKET_ENROLL_SECRET", "POCKET_DEVICE_ID", "POCKET_DEVICE_NAME", "POCKET_HOME"]) delete process.env[k];
    fs.rmSync(ciHome, { recursive: true, force: true });
  }
});

test("clear errors", async () => {
  const empty = fs.mkdtempSync(path.join(os.tmpdir(), "pocket-node-empty-"));
  try {
    await assert.rejects(pocket.connect("node-x", { home: empty }), /not set up/);
    await assert.rejects(pocket.connect("node-x", { home, enrollSecret: "wrong" }), /refused/);
    await assert.rejects(pocket.connect("node-x", { home, endpoint: "http://127.0.0.1:9" }), /can't be reached/);
  } finally {
    fs.rmSync(empty, { recursive: true, force: true });
  }
});
