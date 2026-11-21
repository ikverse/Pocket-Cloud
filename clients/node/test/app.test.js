"use strict";
// pocket.app(): the shared surface of docs/app-api.md section 10, against the fake server of section 11.

const { test, before, after, beforeEach } = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const http = require("http");
const path = require("path");
const crypto = require("crypto");
const pocket = require("..");
const { startServer } = require("./helpers");
const { rewriteLatestYml } = require("../lib/app");

let env;
before(async () => {
  env = await startServer();
});
after(() => env.close());
beforeEach(async () => {
  if (env.isFake) await env.control("reset");
});

const fakeOnly = () => ({ skip: env && !env.isFake ? "uses /_fake/ controls" : false });
const etagOf = (s) => `"${crypto.createHash("md5").update(s).digest("hex")}"`;
const collect = async (it) => {
  const out = [];
  for await (const x of it) out.push(x);
  return out;
};
const waitFor = async (fn, ms = 5000) => {
  const until = Date.now() + ms;
  for (;;) {
    if (await fn()) return;
    if (Date.now() > until) throw new Error("timed out waiting");
    await new Promise((r) => setTimeout(r, 20));
  }
};

test("open enrolls once, learns the features, and files round-trip", async () => {
  const home = env.home();
  const a = await env.open("node-basic", { home });
  assert.ok(a.features.has("files") && a.features.has("changes"));
  const e = await a.put("notes/hello world.txt", "hello", { contentType: "text/plain" });
  assert.deepStrictEqual({ ...e, modified: typeof e.modified }, {
    bucket: "shared", key: "notes/hello world.txt", size: 5, etag: '"5d41402abc4b2a76b9719d911017c592"', modified: "number",
  });
  assert.strictEqual((await a.get("notes/hello world.txt")).toString(), "hello");
  const st = await a.stat("notes/hello world.txt");
  assert.strictEqual(st.size, 5);
  assert.strictEqual(st.etag, e.etag);
  assert.strictEqual(await a.stat("nope.txt"), null);
  await assert.rejects(a.get("nope.txt"), (err) => err instanceof pocket.NotFound && err.code === "NoSuchKey" && err.status === 404 && err.name === "NotFound");
  await a.delete("notes/hello world.txt");
  await assert.rejects(a.delete("notes/hello world.txt"), pocket.NotFound);
  await env.open("node-basic", { home }); // saved key is reused
  if (env.isFake) assert.strictEqual(env.fake.enrolls, 1);
});

test("unicode and odd keys survive the path encoding", async () => {
  const a = await env.open("node-keys");
  for (const k of ["a b/c+d&e=f.txt", "ünï/çødé €.txt", "x/(1)!'*~.bin"]) {
    await a.put(k, k);
    assert.strictEqual((await a.get(k)).toString(), k);
  }
  await assert.rejects(a.put("a//b", "x"), (e) => e.code === "BadKey");
  await assert.rejects(a.get("x", { bucket: "nope" }), (e) => e instanceof pocket.NotFound && e.code === "NoSuchBucket");
});

test("putFile and getFile stream and keep the file's time", async () => {
  const a = await env.open("node-files");
  const dir = env.tmp();
  const src = path.join(dir, "big.bin");
  const data = crypto.randomBytes(3 * 1024 * 1024 + 7);
  fs.writeFileSync(src, data);
  const when = new Date("2026-01-02T03:04:05Z");
  fs.utimesSync(src, when, when);
  const e = await a.putFile("blobs/big.bin", src, { bucket: "device" });
  assert.strictEqual(e.size, data.length);
  assert.strictEqual(e.etag, `"${crypto.createHash("md5").update(data).digest("hex")}"`);
  assert.strictEqual(Math.round(e.modified), when.getTime() / 1000);
  const dest = path.join(dir, "out", "copy.bin");
  const got = await a.getFile("blobs/big.bin", dest, { bucket: "device" });
  assert.ok(fs.readFileSync(dest).equals(data));
  assert.strictEqual(Math.round(fs.statSync(dest).mtimeMs / 1000), when.getTime() / 1000);
  assert.strictEqual(got.etag, e.etag);
  assert.deepStrictEqual(fs.readdirSync(path.join(dir, "out")), ["copy.bin"]);
});

test("list: every page, byte order, folders", async () => {
  const a = await env.open("node-list");
  const keys = ["notes/b.txt", "notes/a.txt", "notes/2026/x.txt", "notes/2026/y.txt", "notes/Z.txt", "other.txt"];
  for (const k of keys) await a.put(k, k);
  const all = await collect(a.list("notes/", { limit: 2 }));
  assert.deepStrictEqual(all.map((e) => e.key), ["notes/2026/x.txt", "notes/2026/y.txt", "notes/Z.txt", "notes/a.txt", "notes/b.txt"]);
  assert.strictEqual(all[0].size, "notes/2026/x.txt".length);
  const top = await collect(a.list("notes/", { folders: true }));
  assert.deepStrictEqual(top.map((e) => [e.key, e.size]), [["notes/2026/", null], ["notes/Z.txt", 11], ["notes/a.txt", 11], ["notes/b.txt", 11]]);
  assert.strictEqual((await collect(a.list())).length, 6);
});

test("conditional writes and deletes", async () => {
  const a = await env.open("node-cond");
  const e1 = await a.put("c.txt", "one", { ifNoneMatch: "*" });
  await assert.rejects(a.put("c.txt", "two", { ifNoneMatch: "*" }), (e) => e instanceof pocket.PreconditionFailed && e.status === 412);
  await assert.rejects(a.put("c.txt", "two", { ifMatch: '"0000"' }), pocket.PreconditionFailed);
  const e2 = await a.put("c.txt", "two", { ifMatch: e1.etag });
  await assert.rejects(a.delete("c.txt", { ifMatch: e1.etag }), pocket.PreconditionFailed);
  await a.delete("c.txt", { ifMatch: e2.etag });
});

test("move: rename, refuse an existing target, overwrite, across buckets", async () => {
  const a = await env.open("node-move");
  await a.put("inbox/a.txt", "a");
  await a.put("done/a.txt", "old");
  await assert.rejects(a.move("inbox/a.txt", "done/a.txt"), (e) => e instanceof pocket.PreconditionFailed && e.code === "KeyExists" && e.status === 409);
  const m = await a.move("inbox/a.txt", "done/a.txt", { overwrite: true });
  assert.deepStrictEqual([m.bucket, m.key, m.size, m.etag], ["shared", "done/a.txt", 1, etagOf("a")]);
  assert.strictEqual(await a.stat("inbox/a.txt"), null);
  await a.move("done/a.txt", "mine/a.txt", { toBucket: "device" });
  assert.strictEqual((await a.get("mine/a.txt", { bucket: "device" })).toString(), "a");
  await assert.rejects(a.move("gone.txt", "x.txt"), pocket.NotFound);
  await assert.rejects(a.move("mine/a.txt", "x.txt", { bucket: "device", ifMatch: '"x"' }), pocket.PreconditionFailed);
});

test("getJson / putJson", async () => {
  const a = await env.open("node-json");
  await a.putJson("cfg/settings.json", { theme: "dark", n: [1, 2] });
  assert.deepStrictEqual(await a.getJson("cfg/settings.json"), { theme: "dark", n: [1, 2] });
  assert.strictEqual(await a.getJson("cfg/missing.json", { default: null }), null);
  assert.deepStrictEqual(await a.getJson("cfg/missing.json", { default: {} }), {});
  await assert.rejects(a.getJson("cfg/missing.json"), pocket.NotFound);
});

test("the device bucket is each device's own", async () => {
  const one = await env.open("node-dev", { deviceId: "device-one-0001" });
  const two = await env.open("node-dev", { deviceId: "device-two-0002" });
  await one.put("me.txt", "one", { bucket: "device" });
  await assert.rejects(two.get("me.txt", { bucket: "device" }), pocket.NotFound);
  await one.put("all.txt", "shared");
  assert.strictEqual((await two.get("all.txt")).toString(), "shared");
});

test("backup keeps the newest, restoreLatest brings the newest back", async () => {
  const a = await env.open("node-backup");
  const dir = env.tmp();
  const db = path.join(dir, "app.db");
  for (const s of ["20250101-000000", "20250102-000000", "20250103-000000"]) await a.put(`backups/app.db/${s}-app.db`, s);
  fs.writeFileSync(db, "newest");
  const e = await a.backup(db, { keep: 2 });
  assert.match(e.key, /^backups\/app\.db\/\d{8}-\d{6}-app\.db$/);
  const left = (await collect(a.list("backups/app.db/"))).map((x) => x.key);
  assert.deepStrictEqual(left, ["backups/app.db/20250103-000000-app.db", e.key]);
  const out = path.join(dir, "restored.db");
  const r = await a.restoreLatest("backups/app.db/", out);
  assert.strictEqual(r.key, e.key);
  assert.strictEqual(fs.readFileSync(out, "utf8"), "newest");
  assert.strictEqual(await a.restoreLatest("backups/none/", out), null);
});

test("change feed: cursor now, then what happened, with visibility", async () => {
  const one = await env.open("node-feed", { deviceId: "feed-dev-0001" });
  const two = await env.open("node-feed", { deviceId: "feed-dev-0002" });
  const start = one.changes();
  assert.deepStrictEqual(await collect(start), []);
  assert.ok(start.cursor);
  await two.put("inbox/a.txt", "a");
  await two.put("secret.txt", "x", { bucket: "device" });
  await two.move("inbox/a.txt", "done/a.txt");
  await two.delete("done/a.txt");
  const feed = one.changes({ since: start.cursor, limit: 1 });
  const got = await collect(feed);
  assert.deepStrictEqual(got.map((c) => [c.kind, c.bucket, c.key]), [["put", "shared", "inbox/a.txt"], ["move", "shared", "done/a.txt"], ["delete", "shared", "done/a.txt"]]);
  assert.deepStrictEqual(got[1].from, { bucket: "shared", key: "inbox/a.txt" });
  assert.strictEqual(got[0].device, "feed-dev-0002");
  assert.strictEqual(got[0].etag, etagOf("a"));
  assert.strictEqual(got[2].etag, null);
  assert.ok(got[0].seq < got[1].seq);
  assert.deepStrictEqual(await collect(one.changes({ since: feed.cursor })), []);
  // two sees its own device bucket
  const mine = await collect(two.changes({ since: start.cursor }));
  assert.ok(mine.some((c) => c.bucket === "device" && c.key === "secret.txt"));
});

test("change feed: long poll answers when a change arrives", async () => {
  const a = await env.open("node-wait");
  const f0 = a.changes();
  await collect(f0);
  const t0 = Date.now();
  setTimeout(() => a.put("late.txt", "x"), 300);
  const got = await collect(a.changes({ since: f0.cursor, wait: 10 }));
  assert.deepStrictEqual(got.map((c) => c.key), ["late.txt"]);
  assert.ok(Date.now() - t0 < 5000);
});

test("change feed: an expired cursor says where to carry on", fakeOnly(), async () => {
  const a = await env.open("node-expire");
  const f0 = a.changes();
  await collect(f0);
  await a.put("a.txt", "1");
  await a.put("b.txt", "2");
  const f1 = a.changes({ since: f0.cursor });
  await collect(f1);
  await env.control("expire-changes", { before: f1.cursor });
  const err = await collect(a.changes({ since: f0.cursor })).catch((e) => e);
  assert.ok(err instanceof pocket.CursorExpired);
  assert.strictEqual(err.status, 410);
  assert.strictEqual(err.cursor, f1.cursor);
  assert.deepStrictEqual(await collect(a.changes({ since: err.cursor })), []);
});

test("onChange follows the feed by long poll", async () => {
  const one = await env.open("node-follow", { deviceId: "follow-dev-001" });
  const two = await env.open("node-follow", { deviceId: "follow-dev-002" });
  const seen = [];
  const h = one.onChange((c) => seen.push(c.key));
  await h.ready;
  await two.put("x.txt", "1");
  await two.put("y.txt", "2");
  await waitFor(() => seen.length >= 2);
  h.stop();
  assert.deepStrictEqual(seen, ["x.txt", "y.txt"]);
});

test("onChange resyncs after CursorExpired", fakeOnly(), async () => {
  const a = await env.open("node-resync");
  const f0 = a.changes();
  await collect(f0);
  await a.put("a.txt", "1");
  const f1 = a.changes({ since: f0.cursor });
  await collect(f1);
  await env.control("expire-changes", { before: f1.cursor });
  const resyncs = [];
  const seen = [];
  const h = a.onChange((c) => seen.push(c.key), { since: f0.cursor, onResync: (cur) => resyncs.push(cur) });
  await h.ready;
  await waitFor(() => resyncs.length === 1);
  await a.put("b.txt", "2");
  await waitFor(() => seen.includes("b.txt"));
  h.stop();
  assert.deepStrictEqual(resyncs, [f1.cursor]);
  assert.deepStrictEqual(seen, ["b.txt"]);
});

test("onChange listens on the push topic when there is one", fakeOnly(), async () => {
  // a stand-in for the push server: GET /<topic>/json kept open, newline-delimited JSON
  const streams = [];
  const paths = [];
  const push = http.createServer((req, res) => {
    paths.push(req.url);
    res.writeHead(200, { "content-type": "application/x-ndjson" });
    res.write(JSON.stringify({ event: "open", topic: "pocket-node-push" }) + "\n");
    streams.push(res);
  });
  await new Promise((r) => push.listen(0, "127.0.0.1", r));
  try {
    await env.control("app", { push: { url: `http://127.0.0.1:${push.address().port}`, topic: "pocket-node-push" } }, "node-push");
    const one = await env.open("node-push", { deviceId: "push-dev-0001" });
    const two = await env.open("node-push", { deviceId: "push-dev-0002" });
    const seen = [];
    const h = one.onChange((c) => seen.push(c.key));
    await h.ready;
    await waitFor(() => streams.length === 1);
    assert.deepStrictEqual(paths, ["/pocket-node-push/json"]);
    await two.put("pushed.txt", "1");
    await new Promise((r) => setTimeout(r, 200));
    assert.deepStrictEqual(seen, [], "nothing is fetched until push says so");
    streams[0].write(JSON.stringify({ event: "message", topic: "pocket-node-push", message: '{"app":"node-push","changes":1}' }) + "\n");
    await waitFor(() => seen.length === 1);
    h.stop();
    assert.deepStrictEqual(seen, ["pushed.txt"]);
    // this app's only: a long poll another test stopped can still finish (and be logged) during this one
    const polls = (await env.requests()).filter((r) => r.app === "node-push" && r.path === "/_pocket/changes" && /wait=/.test(r.query));
    assert.strictEqual(polls.length, 0, "no long polls while push works");
  } finally {
    push.closeAllConnections();
    await new Promise((r) => push.close(r));
  }
});

test("publish, latest, download, and the key-less folder", fakeOnly(), async () => {
  await env.control("app", { publish: true }, "node-pub");
  const a = await env.open("node-pub");
  const dir = env.tmp();
  const exe = path.join(dir, "Node Pub Setup 1.2.3.exe");
  fs.writeFileSync(exe, crypto.randomBytes(5000));
  const blockmap = exe + ".blockmap";
  fs.writeFileSync(blockmap, "blockmap");
  const yml = path.join(dir, "latest.yml");
  fs.writeFileSync(yml, [
    "version: 1.2.3",
    "files:",
    "  - url: Node-Pub-Setup-1.2.3.exe",
    "    sha512: abc==",
    "    size: 5000",
    "path: 'Node-Pub-Setup-1.2.3.exe'",
    "sha512: abc==",
    "releaseDate: '2026-11-20T10:00:00.000Z'",
    "",
  ].join("\n"));
  const url = await a.releasesUrl();
  assert.strictEqual(url, `${env.endpoint}/_pocket/pub/node-pub/`);
  const rel = await a.publish(exe, "1.2.3", { notes: "First.", platform: "windows-x64", extra: [blockmap, yml] });
  assert.strictEqual(rel.version, "1.2.3");
  assert.strictEqual(rel.files[0].path, "1.2.3/Node Pub Setup 1.2.3.exe");
  assert.strictEqual(rel.files[0].url, `${url}1.2.3/Node%20Pub%20Setup%201.2.3.exe`);

  // what electron-updater's generic provider reads, without a key
  const ymlRes = await fetch(url + "latest.yml");
  assert.strictEqual(ymlRes.status, 200);
  assert.strictEqual(ymlRes.headers.get("cache-control"), "no-cache");
  const ymlText = await ymlRes.text();
  assert.match(ymlText, /^ {2}- url: 1\.2\.3\/Node-Pub-Setup-1\.2\.3\.exe$/m);
  assert.match(ymlText, /^path: '1\.2\.3\/Node-Pub-Setup-1\.2\.3\.exe'$/m);
  assert.match(ymlText, /^releaseDate: '2026-11-20T10:00:00.000Z'$/m);
  assert.strictEqual((await fetch(`${url}1.2.3/${encodeURIComponent(path.basename(blockmap))}`)).status, 200);
  const lj = await (await fetch(url + "latest.json")).json();
  assert.deepStrictEqual([lj.app, lj.version, lj.notes, lj.files.length, lj.files[0].platform], ["node-pub", "1.2.3", "First.", 1, "windows-x64"]);

  const latest = await a.releases().latest({ platform: "windows-x64" });
  assert.strictEqual(latest.version, "1.2.3");
  assert.strictEqual(await a.releases().latest({ platform: "android-arm64" }), null);
  const out = await latest.download(env.tmp());
  assert.ok(fs.readFileSync(out).equals(fs.readFileSync(exe)));

  // a second platform's build of the same version joins it
  const apk = path.join(dir, "node-pub-1.2.3.apk");
  fs.writeFileSync(apk, "apk");
  await a.publish(apk, "1.2.3", { platform: "android-arm64" });
  const both = await a.releases().latest();
  assert.deepStrictEqual(both.files.map((f) => f.name).sort(), ["Node Pub Setup 1.2.3.exe", "node-pub-1.2.3.apk"]);
  assert.strictEqual(both.notes, "First.");
  await assert.rejects(both.download(env.tmp()), (e) => e.code === "BadRequest");

  // a file that does not match latest.json is refused
  await a.put("releases/1.2.3/node-pub-1.2.3.apk", "tampered");
  const dest = env.tmp();
  await assert.rejects(both.download(dest, { name: "node-pub-1.2.3.apk" }), (e) => e.code === "ChecksumMismatch" && e instanceof pocket.PocketError);
  assert.deepStrictEqual(fs.readdirSync(dest), []);

  // a new version replaces the manifest
  await a.publish(apk, "1.2.4", { notes: "" });
  assert.strictEqual((await a.releases().latest()).files.length, 1);
  await assert.rejects(a.publish(apk, "1 2"), (e) => e.code === "BadRequest");
});

test("releases work through the signed route when the folder is off", async () => {
  const a = await env.open("node-pub-off");
  assert.strictEqual(await a.releases().latest(), null);
  const f = path.join(env.tmp(), "tool.zip");
  fs.writeFileSync(f, "zip");
  const rel = await a.publish(f, "0.1.0");
  assert.strictEqual(rel.files[0].url, null);
  const latest = await a.releases().latest();
  assert.strictEqual(latest.files[0].url, null);
  const out = await latest.download(path.join(env.tmp(), "tool.zip"));
  assert.strictEqual(fs.readFileSync(out, "utf8"), "zip");
  if (env.isFake) assert.strictEqual((await fetch(`${env.endpoint}/_pocket/pub/node-pub-off/latest.json`)).status, 404);
});

test("rewriteLatestYml leaves everything else alone", () => {
  const src = "version: 2.0.0\r\nfiles:\r\n  - url: A-2.0.0.dmg\r\n  - url: https://x/y.zip\r\n  - url: sub/B.zip\r\npath: A-2.0.0.dmg\r\nnotes:\r\n  path: keep\r\n";
  assert.strictEqual(
    rewriteLatestYml(src, "2.0.0"),
    "version: 2.0.0\r\nfiles:\r\n  - url: 2.0.0/A-2.0.0.dmg\r\n  - url: https://x/y.zip\r\n  - url: sub/B.zip\r\npath: 2.0.0/A-2.0.0.dmg\r\nnotes:\r\n  path: keep\r\n"
  );
});

test("notify: off by default, then sent, then rate limited", fakeOnly(), async () => {
  const a = await env.open("node-notify");
  await assert.rejects(a.notify("hi"), (e) => e instanceof pocket.FeatureOff && e.code === "FeatureOff");
  await env.control("app", { notify: true }, "node-notify");
  const r = await a.notify("6 new calls", { title: "Evening", tags: ["chart"], priority: 4, click: "egx://calls" });
  assert.strictEqual(r.topic, "pocket-node-notify-notify");
  const sent = await env.notifications();
  assert.deepStrictEqual(sent.map((n) => [n.message, n.title, n.priority, n.click]), [["6 new calls", "Evening", 4, "egx://calls"]]);
  for (let i = 0; i < 9; i++) await a.notify(`n${i}`);
  const err = await a.notify("one too many").catch((e) => e);
  assert.ok(err instanceof pocket.RateLimited);
  assert.ok(err.retryAfter > 0);
  const tries = (await env.requests()).filter((x) => x.path === "/_pocket/notify");
  assert.strictEqual(tries.length, 12, "notify is not retried");
});

test("quota, QuotaExceeded and ReadOnly", fakeOnly(), async () => {
  const a = await env.open("node-quota");
  await a.put("a.bin", Buffer.alloc(100));
  const q = await a.quota();
  assert.strictEqual(q.used_app, 100);
  assert.strictEqual(q.allocation, 1073741824);
  await env.control("app", { allocation: 150 }, "node-quota");
  await assert.rejects(a.put("b.bin", Buffer.alloc(100)), (e) => e instanceof pocket.QuotaExceeded && e.status === 403);
  await env.control("app", { read_only: true }, "node-quota");
  await assert.rejects(a.put("c.bin", "x"), (e) => e instanceof pocket.ReadOnly && e.code === "ReadOnly");
  await assert.rejects(a.delete("a.bin"), pocket.ReadOnly);
});

test("share makes a link", async () => {
  const a = await env.open("node-share");
  await a.put("report.txt", "r");
  const s = await a.share("report.txt", { expires: 3600, maxDownloads: 2 });
  assert.ok(s.token && s.url);
  assert.strictEqual(s.max_downloads, 2);
  await assert.rejects(a.share("missing.txt"), pocket.NotFound);
});

test("reads retry 503, unconditional writes do not", fakeOnly(), async () => {
  const a = await env.open("node-retry");
  await a.put("r.txt", "r");
  await env.control("fail", { route: "GET /_pocket/files", status: 503, code: "ServiceUnavailable", times: 2, retry_after: 0 });
  assert.strictEqual((await a.get("r.txt")).toString(), "r");
  const gets = (await env.requests()).filter((x) => x.method === "GET" && x.path === "/_pocket/files/shared/r.txt");
  assert.deepStrictEqual(gets.map((x) => x.status), [503, 503, 200]);
  await env.control("fail", { route: "PUT /_pocket/files", status: 503, code: "ServiceUnavailable", times: 1, retry_after: 0 });
  await assert.rejects(a.put("w.txt", "w"), (e) => e instanceof pocket.Unreachable && e.code === "ServiceUnavailable");
  await env.control("fail", { route: "PUT /_pocket/files", status: 503, code: "ServiceUnavailable", times: 1, retry_after: 0 });
  await a.put("w.txt", "w", { ifNoneMatch: "*" }); // conditional: retried
  await env.control("fail", { route: "GET /_pocket/files", status: 503, code: "ServiceUnavailable", times: 4, retry_after: 0 });
  await assert.rejects(a.get("r.txt"), pocket.Unreachable);
});

test("a moved device enrolls again once and retries", fakeOnly(), async () => {
  const a = await env.open("node-moved");
  await a.put("m.txt", "m");
  const before = env.fake.enrolls;
  await env.control("moved", { times: 1 });
  assert.strictEqual((await a.get("m.txt")).toString(), "m");
  assert.strictEqual(env.fake.enrolls, before + 1);
  await env.control("moved", { times: 2 });
  await assert.rejects(a.get("m.txt"), (e) => e instanceof pocket.AccessDenied && e.code === "AccessDenied");
});

test("a replaced key enrolls again", fakeOnly(), async () => {
  const home = env.home();
  const a = await env.open("node-rekey", { home });
  await a.put("k.txt", "k");
  await env.control("reset");
  assert.strictEqual((await a.get("k.txt").catch((e) => e)).code, "NoSuchKey"); // new key, empty store
  const saved = JSON.parse(fs.readFileSync(path.join(home, "apps", "node-rekey.json"), "utf8"));
  assert.ok(env.fake.keys.has(saved.access_key));
});

test("missing features raise FeatureMissing", fakeOnly(), async () => {
  await env.control("features", { features: ["quota", "conflicts", "mirror-report", "share", "push", "files"] });
  const a = await env.open("node-old");
  await a.put("ok.txt", "fine");
  for (const p of [a.move("ok.txt", "x.txt"), collect(a.changes()), a.notify("x"), a.releasesUrl()]) {
    await assert.rejects(p, (e) => e instanceof pocket.FeatureOff && e.code === "FeatureMissing" && e.status === 0);
  }
});

test("clock skew and a bad body digest are refused", fakeOnly(), async () => {
  const a = await env.open("node-skew");
  await env.control("clock", { now: Math.floor(Date.now() / 1000) + 3600 });
  await assert.rejects(a.get("x"), (e) => e instanceof pocket.AccessDenied && e.code === "RequestTimeTooSkewed");
  await env.control("clock", { now: null });
  // a raw upload whose body does not match the hash it was signed with
  const { signPocket, sha256hex } = require("../lib/sign");
  const p = "/_pocket/files/shared/d.txt";
  const sha = sha256hex("expected");
  const res = await fetch(env.endpoint + p, {
    method: "PUT",
    headers: { "x-pocket-content-sha256": sha, ...signPocket("PUT", p, "", sha, a.dev.creds.access_key, a.dev.creds.secret_key) },
    body: "something else",
  });
  assert.strictEqual(res.status, 400);
  assert.strictEqual((await res.json()).error, "BadDigest");
  assert.strictEqual(await a.stat("d.txt"), null);
});

test("an unreachable server: open still works when enrolled, calls say Unreachable", async () => {
  const home = env.home();
  await env.open("node-offline", { home });
  const a = await pocket.app("node-offline", { home, endpoint: env.endpoint, enrollSecret: env.secret, timeoutMs: 2000 });
  a.dev.endpoint = "http://127.0.0.1:9"; // nothing listens there
  await assert.rejects(a.put("x", "y"), (e) => e instanceof pocket.Unreachable && e.status === 0);
});

// ---------- sync ----------

const write = (root, rel, text, t) => {
  const f = path.join(root, ...rel.split("/"));
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, text);
  if (t) fs.utimesSync(f, t, t);
};
const read = (root, rel) => fs.readFileSync(path.join(root, ...rel.split("/")), "utf8");
const exists = (root, rel) => fs.existsSync(path.join(root, ...rel.split("/")));

test("sync: first run copies both ways and never deletes", async () => {
  const a = await env.open("node-sync1");
  const root = env.tmp();
  write(root, "local.txt", "L");
  write(root, "sub/both.txt", "same");
  write(root, "differ.txt", "local version");
  write(root, ".pocket-private", "never uploaded");
  await a.put("notes/remote.txt", "R");
  await a.put("notes/sub/both.txt", "same");
  await a.put("notes/differ.txt", "remote version");
  const r = await a.sync(root, { prefix: "notes/" });
  assert.deepStrictEqual(r.uploaded, ["notes/local.txt"]);
  assert.deepStrictEqual(r.downloaded, ["notes/differ.txt", "notes/remote.txt"]);
  assert.deepStrictEqual([r.deletedLocal, r.deletedRemote], [[], []]);
  assert.strictEqual(r.conflicts.length, 2);
  assert.strictEqual(r.conflicts[0], "notes/differ.txt");
  assert.match(r.conflicts[1], /^notes\/differ \(conflict Node test \d{4}-\d\d-\d\d \d{4}\)\.txt$/);
  assert.strictEqual(read(root, "differ.txt"), "remote version");
  assert.strictEqual((await a.get(r.conflicts[1])).toString(), "local version");
  assert.strictEqual(read(root, "remote.txt"), "R");
  assert.strictEqual(await a.stat("notes/.pocket-private"), null);
  assert.ok(exists(root, ".pocket-sync.json"));
  // nothing more to do
  const again = await a.sync(root, { prefix: "notes/" });
  assert.deepStrictEqual(again, { uploaded: [], downloaded: [], deletedLocal: [], deletedRemote: [], conflicts: [] });
});

async function syncedPair(app) {
  const a = await env.open(app);
  const root = env.tmp();
  write(root, "keep.txt", "k");
  write(root, "gone-local.txt", "1");
  write(root, "edit.txt", "v1");
  await a.put("gone-remote.txt", "2");
  await a.sync(root);
  return { a, root };
}

test("sync: later runs carry edits and deletes both ways", async () => {
  const { a, root } = await syncedPair("node-sync2");
  fs.rmSync(path.join(root, "gone-local.txt"));
  await a.delete("gone-remote.txt");
  write(root, "edit.txt", "v2", new Date(Date.now() + 5000));
  await a.put("new-remote.txt", "n");
  const r = await a.sync(root);
  assert.deepStrictEqual(r.uploaded, ["edit.txt"]);
  assert.deepStrictEqual(r.downloaded, ["new-remote.txt"]);
  assert.deepStrictEqual(r.deletedLocal, ["gone-remote.txt"]);
  assert.deepStrictEqual(r.deletedRemote, ["gone-local.txt"]);
  assert.deepStrictEqual(r.conflicts, []);
  assert.strictEqual((await a.get("edit.txt")).toString(), "v2");
  assert.ok(!exists(root, "gone-remote.txt"));
  assert.strictEqual(await a.stat("gone-local.txt"), null);
});

test("sync: changed on both sides becomes a conflict", async () => {
  const { a, root } = await syncedPair("node-sync3");
  write(root, "edit.txt", "mine", new Date(Date.now() + 5000));
  await a.put("edit.txt", "theirs");
  const r = await a.sync(root);
  assert.strictEqual(r.conflicts[0], "edit.txt");
  assert.strictEqual(read(root, "edit.txt"), "theirs");
  assert.strictEqual((await a.get(r.conflicts[1])).toString(), "mine");
});

test("sync: the mass-delete guard holds deletes back", async () => {
  const a = await env.open("node-sync4");
  const root = env.tmp();
  for (let i = 0; i < 52; i++) write(root, `f${i}.txt`, String(i));
  await a.sync(root);
  for (let i = 0; i < 52; i++) fs.rmSync(path.join(root, `f${i}.txt`));
  write(root, "new.txt", "new");
  const err = await a.sync(root).catch((e) => e);
  assert.ok(err instanceof pocket.PocketError);
  assert.strictEqual(err.code, "MassDelete");
  assert.strictEqual(err.count, 52);
  assert.deepStrictEqual(err.result.uploaded, ["new.txt"]);
  assert.strictEqual((await collect(a.list())).length, 53);
  const r = await a.sync(root, { allowMassDelete: true });
  assert.strictEqual(r.deletedRemote.length, 52);
  assert.deepStrictEqual((await collect(a.list())).map((e) => e.key), ["new.txt"]);
});

test("sync: without the change feed, and after CursorExpired, a full listing", fakeOnly(), async () => {
  const { a, root } = await syncedPair("node-sync5");
  await a.put("later.txt", "l");
  const now = a.changes();
  await collect(now);
  await env.control("expire-changes", { before: now.cursor }); // the saved cursor is older
  const r = await a.sync(root);
  assert.deepStrictEqual(r.downloaded, ["later.txt"]);
  await env.control("features", { features: ["files", "move", "quota", "share"] });
  const b = await env.open("node-sync5", { home: undefined });
  await b.delete("later.txt");
  const r2 = await b.sync(root);
  assert.deepStrictEqual(r2.deletedLocal, ["later.txt"]);
});

// ---------- review fixes ----------

test("sync: local deletes held back by the guard go through on a later run", async () => {
  const a = await env.open("node-sync6");
  const root = env.tmp();
  for (let i = 0; i < 52; i++) await a.put(`f${i}.txt`, String(i));
  await a.sync(root);
  for (let i = 0; i < 52; i++) await a.delete(`f${i}.txt`); // another device deleted them
  for (let pass = 0; pass < 2; pass++) {
    const err = await a.sync(root).catch((e) => e);
    assert.strictEqual(err.code, "MassDelete", `pass ${pass}: still held`);
    assert.strictEqual(err.count, 52);
    assert.strictEqual(fs.readdirSync(root).filter((n) => n.endsWith(".txt")).length, 52);
  }
  const r = await a.sync(root, { allowMassDelete: true });
  assert.strictEqual(r.deletedLocal.length, 52);
  assert.deepStrictEqual(fs.readdirSync(root).filter((n) => n.endsWith(".txt")), []);
});

test("sync: two conflicts in the same minute both survive", async () => {
  const { a, root } = await syncedPair("node-sync7");
  const copies = [];
  for (const n of [1, 2]) {
    write(root, "edit.txt", `mine ${n}`, new Date(Date.now() + 5000 * n));
    await a.put("edit.txt", `theirs ${n}`);
    const r = await a.sync(root);
    copies.push(r.conflicts[1]);
  }
  assert.notStrictEqual(copies[0], copies[1]);
  assert.match(copies[1], / 2\)\.txt$/);
  assert.strictEqual((await a.get(copies[0])).toString(), "mine 1");
  assert.strictEqual((await a.get(copies[1])).toString(), "mine 2");
  assert.strictEqual(read(root, copies[0]), "mine 1");
});

test("backup keeps the one just written even when the device clock is behind", async () => {
  const a = await env.open("node-backup-clock");
  for (const s of ["29990101-000000", "29990102-000000", "29990103-000000"]) await a.put(`backups/app.db/${s}-app.db`, s);
  const db = path.join(env.tmp(), "app.db");
  fs.writeFileSync(db, "now");
  const e = await a.backup(db, { keep: 2 });
  const left = (await collect(a.list("backups/app.db/"))).map((x) => x.key);
  assert.deepStrictEqual(left.sort(), [e.key, "backups/app.db/29990103-000000-app.db"].sort());
});

test("SharesOff is FeatureOff", () => {
  const err = require("../lib/errors").fromResponse(403, "SharesOff", { message: "off" });
  assert.ok(err instanceof pocket.FeatureOff);
  assert.strictEqual(err.code, "SharesOff");
});

test("an upload slower than timeoutMs succeeds while it makes progress", fakeOnly(), async () => {
  const a = await env.open("node-slow", { timeoutMs: 1000 });
  const src = path.join(env.tmp(), "slow.bin");
  const data = crypto.randomBytes(4 * 1024 * 1024);
  fs.writeFileSync(src, data);
  await env.control("slow", { ms: 30 });
  const t0 = Date.now();
  try {
    const e = await a.putFile("slow.bin", src);
    assert.strictEqual(e.size, data.length);
    assert.ok(Date.now() - t0 > 1500, `took ${Date.now() - t0} ms, should be well over timeoutMs`);
    const e2 = await a.put("slow2.bin", data); // a Buffer body too
    assert.strictEqual(e2.etag, e.etag);
  } finally {
    await env.control("slow", { ms: 0 });
  }
});

test("after a streamed upload, the server gets extra time to save it; a small request does not", fakeOnly(), async () => {
  const a = await env.open("node-save", { timeoutMs: 500 });
  const src = path.join(env.tmp(), "big.bin");
  const data = crypto.randomBytes(3 * 1024 * 1024); // streamed: 500 ms idle + 3 s to save
  fs.writeFileSync(src, data);
  await env.control("save", { ms: 1500 });
  try {
    const e = await a.putFile("big.bin", src);
    assert.strictEqual(e.size, data.length);
    // a body small enough to go in one piece is not streamed: the plain 500 ms applies
    await assert.rejects(a.put("small.txt", "tiny"), (err) => err instanceof pocket.Unreachable);
  } finally {
    await env.control("save", { ms: 0 });
  }
});

test("a download that stalls or breaks off mid-body is retried", fakeOnly(), async () => {
  const a = await env.open("node-break", { timeoutMs: 500 });
  const data = crypto.randomBytes(200 * 1024);
  await a.put("b.bin", data);
  await env.control("break", { route: "GET /_pocket/files/shared/b.bin", mode: "stall", times: 1 });
  assert.ok((await a.get("b.bin")).equals(data));
  await env.control("break", { route: "GET /_pocket/files/shared/b.bin", mode: "cut", times: 1 });
  const dir = env.tmp();
  await a.getFile("b.bin", path.join(dir, "b.bin"));
  assert.ok(fs.readFileSync(path.join(dir, "b.bin")).equals(data));
  assert.deepStrictEqual(fs.readdirSync(dir), ["b.bin"]);
  // every try breaks: Unreachable, not a hang or a TypeError
  await env.control("break", { route: "GET /_pocket/files/shared/b.bin", mode: "cut", times: 4 });
  await assert.rejects(a.get("b.bin"), (e) => e instanceof pocket.Unreachable && e.code === "Unreachable");
  // a JSON answer that breaks off is retried the same way
  await env.control("break", { route: "GET /_pocket/list", mode: "cut", times: 1 });
  assert.strictEqual((await collect(a.list())).length, 1);
});
