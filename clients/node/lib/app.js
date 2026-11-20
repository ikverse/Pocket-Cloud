"use strict";
// pocket.app(name): the shared library surface on the plain /_pocket/ routes (docs/app-api.md section 10).
// Built-in fetch only; the AWS SDK is used by connect() alone.

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { Readable, Transform } = require("stream");
const { pipeline } = require("stream/promises");
const { EMPTY_SHA256, sha256hex, encode, encodeKey, encodeQuery, signPocket } = require("./sign");
const E = require("./errors");

const { PocketError, NotFound, PreconditionFailed, CursorExpired, FeatureOff, Unreachable } = E;

/** What a 0.2.x server has: it answers health without a feature list. */
const OLD_FEATURES = ["quota", "conflicts", "mirror-report", "share", "push"];
const BACKOFF_MS = [500, 1000, 2000];
const MAX_PUT = 2147483648;
const VERSION = /^[A-Za-z0-9._+-]{1,64}$/;
const INFO_TTL_MS = 60 * 1000;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function fileHash(file) {
  return new Promise((resolve, reject) => {
    const sha = crypto.createHash("sha256");
    const md5 = crypto.createHash("md5");
    let size = 0;
    fs.createReadStream(file)
      .on("data", (d) => {
        sha.update(d);
        md5.update(d);
        size += d.length;
      })
      .on("error", reject)
      .on("end", () => resolve({ sha256: sha.digest("hex"), md5: md5.digest("hex"), size }));
  });
}

function tmpBeside(file) {
  return path.join(path.dirname(file), `.pocket-tmp-${crypto.randomBytes(6).toString("hex")}`);
}

const toBuffer = (data) =>
  typeof data === "string" ? Buffer.from(data, "utf8") : Buffer.isBuffer(data) ? data : Buffer.from(data);

const num = (v) => (v == null || v === "" ? null : Number(v));

/** electron-builder's latest*.yml with each installer url/path pointed into the release's version folder. */
function rewriteLatestYml(text, version) {
  const eol = text.includes("\r\n") ? "\r\n" : "\n";
  return text
    .split(/\r?\n/)
    .map((line) => {
      const m = line.match(/^(\s*(?:-\s+)?)(url|path):(\s*)(.*?)\s*$/);
      if (!m) return line;
      if (m[2] === "path" && m[1] !== "") return line; // only the top-level path
      let v = m[4];
      let q = "";
      if (/^(['"]).*\1$/.test(v)) {
        q = v[0];
        v = v.slice(1, -1);
      }
      if (!v || v.includes("/") || /^[A-Za-z][A-Za-z0-9+.-]*:/.test(v)) return line;
      return `${m[1]}${m[2]}:${m[3]}${q}${version}/${v}${q}`;
    })
    .join(eol);
}

class ChangeFeed {
  constructor(app, o) {
    this._app = app;
    this._o = o;
    /** The cursor to carry on from: after iterating, the one for the next call. */
    this.cursor = o.since ?? null;
  }

  async *[Symbol.asyncIterator]() {
    const app = this._app;
    await app._ensure("changes");
    const wait = this._o.wait || 0;
    for (;;) {
      const res = await app._request("GET", "/_pocket/changes", {
        query: [["since", this.cursor], ["limit", this._o.limit], ["wait", wait || null]],
        retry: true,
        timeoutMs: app.timeoutMs + wait * 1000,
        signal: this._o.signal,
      });
      const d = await res.json();
      this.cursor = d.cursor;
      for (const c of d.changes || []) yield c;
      if (!d.more) return;
    }
  }
}

class PocketApp {
  constructor(dev, opts) {
    this.dev = dev;
    this.name = dev.app;
    this.timeoutMs = opts.timeoutMs || 30000;
    /** @type {Set<string>|null} server features; null until health has answered */
    this.features = null;
    this._info = null;
  }

  // ---------- plumbing ----------

  async _loadFeatures() {
    let res;
    try {
      res = await fetch(`${this.dev.endpoint}/_pocket/health`, { signal: AbortSignal.timeout(this.timeoutMs) });
    } catch (e) {
      throw new Unreachable(`Pocket Cloud can't be reached at ${this.dev.endpoint}: ${e.cause?.code || e.message}`, "Unreachable");
    }
    const body = res.ok ? await res.json().catch(() => ({})) : {};
    this.features = new Set(Array.isArray(body.features) ? body.features : OLD_FEATURES);
    this.version = body.version || null;
    return this.features;
  }

  /** Throw FeatureOff (code FeatureMissing) when the server lacks a feature. */
  async _ensure(feature) {
    if (!this.features) await this._loadFeatures();
    if (!this.features.has(feature)) {
      throw new FeatureOff(`This Pocket Cloud server does not have "${feature}" (update the server).`, "FeatureMissing", 0);
    }
  }

  _filePath(bucket, key) {
    return `/_pocket/files/${encode(bucket || "shared")}/${encodeKey(key)}`;
  }

  async _send(method, urlPath, query, o) {
    const headers = { ...(o.headers || {}) };
    let bodySha = EMPTY_SHA256;
    let body;
    if (o.file) {
      bodySha = o.file.sha256;
      body = Readable.toWeb(fs.createReadStream(o.file.path));
      headers["content-length"] = String(o.file.size);
    } else if (o.body != null) {
      body = toBuffer(o.body);
      bodySha = sha256hex(body);
    }
    if (method === "PUT") headers["x-pocket-content-sha256"] = bodySha;
    const c = this.dev.creds;
    Object.assign(headers, signPocket(method, urlPath, query, bodySha, c.access_key, c.secret_key));
    const url = this.dev.endpoint + urlPath + (query ? "?" + query : "");
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), o.timeoutMs || this.timeoutMs);
    const onAbort = () => ctrl.abort();
    if (o.signal) o.signal.addEventListener("abort", onAbort, { once: true });
    try {
      return await fetch(url, { method, headers, body, duplex: body ? "half" : undefined, signal: ctrl.signal });
    } catch (e) {
      if (o.signal?.aborted) throw new PocketError("Stopped.", "Aborted", 0);
      const why = ctrl.signal.aborted ? "timed out" : e.cause?.code || e.message;
      throw new Unreachable(`Pocket Cloud can't be reached at ${this.dev.endpoint}: ${why}`, "Unreachable");
    } finally {
      clearTimeout(timer);
      if (o.signal) o.signal.removeEventListener("abort", onAbort);
    }
  }

  async _errorOf(res, method) {
    let body = null;
    if (method !== "HEAD") body = await res.json().catch(() => null);
    const code = (body && body.error) || res.headers.get("x-pocket-error-code");
    return E.fromResponse(res.status, code, body, res.headers);
  }

  /**
   * A signed request. o: {query: [[k, v]], body, file: {path, size, sha256}, headers, retry, ok: [status],
   * timeoutMs, signal}. Re-enrolls once when the device moved or its key was replaced; with retry, retries
   * Unreachable / 503 up to 3 times.
   */
  async _request(method, urlPath, o = {}) {
    const query = o.query ? encodeQuery(o.query) : "";
    let attempt = 0;
    let reenrolled = false;
    for (;;) {
      let res = null;
      let err;
      try {
        res = await this._send(method, urlPath, query, o);
      } catch (e) {
        err = e;
      }
      if (res) {
        if (res.ok || (o.ok || []).includes(res.status)) return res;
        err = await this._errorOf(res, method);
        const moved = res.headers.get("x-pocket-error") === "device-address-changed";
        if (!reenrolled && res.status === 403 && (moved || err.code === "InvalidAccessKeyId")) {
          reenrolled = true;
          await this.dev.enroll();
          continue;
        }
      }
      if (o.retry && err instanceof Unreachable && attempt < BACKOFF_MS.length) {
        const ra = res ? E.retryAfterOf(res.headers) : null;
        await sleep(ra != null ? Math.min(ra, 60) * 1000 : BACKOFF_MS[attempt]);
        attempt++;
        continue;
      }
      throw err;
    }
  }

  _entry(bucket, key, d) {
    return { bucket, key, size: d.size ?? null, etag: d.etag ?? null, modified: d.modified ?? null };
  }

  _entryFromHeaders(bucket, key, h) {
    return {
      bucket,
      key,
      size: num(h.get("content-length")),
      etag: h.get("etag"),
      modified: num(h.get("x-pocket-modified")),
    };
  }

  // ---------- what this app may use ----------

  /** GET /_pocket/app: buckets, features, publish/notify/push, retention, read_only. Cached for a minute. */
  async info({ fresh = false } = {}) {
    await this._ensure("files");
    if (!fresh && this._info && Date.now() - this._info.at < INFO_TTL_MS) return this._info.data;
    const res = await this._request("GET", "/_pocket/app", { retry: true });
    const data = await res.json();
    this._info = { at: Date.now(), data };
    return data;
  }

  // ---------- files ----------

  async get(key, { bucket = "shared" } = {}) {
    await this._ensure("files");
    const res = await this._request("GET", this._filePath(bucket, key), { retry: true });
    return Buffer.from(await res.arrayBuffer());
  }

  async getFile(key, dest, { bucket = "shared" } = {}) {
    await this._ensure("files");
    const res = await this._request("GET", this._filePath(bucket, key), { retry: true });
    const entry = this._entryFromHeaders(bucket, key, res.headers);
    fs.mkdirSync(path.dirname(path.resolve(dest)), { recursive: true });
    const tmp = tmpBeside(path.resolve(dest));
    try {
      await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(tmp));
      if (entry.modified) fs.utimesSync(tmp, new Date(), new Date(entry.modified * 1000));
      fs.renameSync(tmp, dest);
    } catch (e) {
      fs.rmSync(tmp, { force: true });
      if (e instanceof PocketError) throw e;
      throw new Unreachable(`The download of ${key} broke off: ${e.message}`, "Unreachable");
    }
    return entry;
  }

  _writeHeaders(o) {
    const h = {};
    if (o.contentType) h["content-type"] = o.contentType;
    if (o.ifMatch) h["if-match"] = o.ifMatch;
    if (o.ifNoneMatch) h["if-none-match"] = o.ifNoneMatch;
    if (o.modified != null) h["x-pocket-modified"] = String(o.modified);
    return h;
  }

  async put(key, data, o = {}) {
    await this._ensure("files");
    const bucket = o.bucket || "shared";
    const res = await this._request("PUT", this._filePath(bucket, key), {
      body: toBuffer(data),
      headers: this._writeHeaders(o),
      retry: !!(o.ifMatch || o.ifNoneMatch),
    });
    return this._entry(bucket, key, await res.json());
  }

  async putFile(key, src, o = {}) {
    await this._ensure("files");
    const bucket = o.bucket || "shared";
    const st = fs.statSync(src);
    if (st.size > MAX_PUT) {
      throw new PocketError("Files over 2 GiB go through S3 multipart: use connect().", "EntityTooLarge", 0);
    }
    const { sha256, size } = await fileHash(src);
    const res = await this._request("PUT", this._filePath(bucket, key), {
      file: { path: src, size, sha256 },
      headers: this._writeHeaders({ modified: st.mtimeMs / 1000, ...o }),
      retry: !!(o.ifMatch || o.ifNoneMatch),
    });
    return this._entry(bucket, key, await res.json());
  }

  async stat(key, { bucket = "shared" } = {}) {
    await this._ensure("files");
    try {
      const res = await this._request("HEAD", this._filePath(bucket, key), { retry: true });
      return this._entryFromHeaders(bucket, key, res.headers);
    } catch (e) {
      if (e instanceof NotFound && e.code === "NoSuchKey") return null;
      throw e;
    }
  }

  async *list(prefix = "", { bucket = "shared", folders = false, limit } = {}) {
    await this._ensure("files");
    let cursor = null;
    do {
      const res = await this._request("GET", "/_pocket/list", {
        query: [["bucket", bucket], ["prefix", prefix || null], ["delimiter", folders ? "/" : null], ["cursor", cursor], ["limit", limit]],
        retry: true,
      });
      const d = await res.json();
      const items = (d.files || []).map((f) => this._entry(bucket, f.key, f));
      for (const k of d.folders || []) items.push({ bucket, key: k, size: null, etag: null, modified: null });
      items.sort((a, b) => Buffer.compare(Buffer.from(a.key), Buffer.from(b.key)));
      yield* items;
      cursor = d.cursor;
    } while (cursor);
  }

  async delete(key, { bucket = "shared", ifMatch } = {}) {
    await this._ensure("files");
    await this._request("DELETE", this._filePath(bucket, key), { headers: this._writeHeaders({ ifMatch }) });
  }

  async move(src, dst, { bucket = "shared", toBucket, overwrite = false, ifMatch } = {}) {
    await this._ensure("move");
    const body = { from: { bucket, key: src }, to: { bucket: toBucket || bucket, key: dst }, overwrite: !!overwrite };
    if (ifMatch) body.if_match = ifMatch;
    const res = await this._request("POST", "/_pocket/move", {
      body: JSON.stringify(body),
      headers: { "content-type": "application/json" },
    });
    const d = await res.json();
    return this._entry(d.to.bucket, d.to.key, d);
  }

  async getJson(key, o = {}) {
    let buf;
    try {
      buf = await this.get(key, o);
    } catch (e) {
      if (e instanceof NotFound && e.code === "NoSuchKey" && "default" in o) return o.default;
      throw e;
    }
    return JSON.parse(buf.toString("utf8"));
  }

  putJson(key, value, o = {}) {
    return this.put(key, JSON.stringify(value), { ...o, contentType: "application/json" });
  }

  // ---------- backups ----------

  async backup(file, { keep = 7, prefix = "backups/", bucket = "shared" } = {}) {
    if (!(keep >= 1)) throw new PocketError("keep is at least 1.", "BadRequest");
    const name = path.basename(file);
    const stamp = new Date().toISOString().replace(/[-:]/g, "").replace("T", "-").slice(0, 15);
    const folder = `${prefix}${name}/`;
    const entry = await this.putFile(`${folder}${stamp}-${name}`, file, { bucket });
    const all = [];
    for await (const e of this.list(folder, { bucket })) {
      if (/^\d{8}-\d{6}-/.test(e.key.slice(folder.length))) all.push(e);
    }
    all.sort((a, b) => (a.key < b.key ? 1 : a.key > b.key ? -1 : 0));
    for (const old of all.slice(keep)) {
      try {
        await this.delete(old.key, { bucket });
      } catch (e) {
        if (!(e instanceof NotFound)) throw e;
      }
    }
    return entry;
  }

  async restoreLatest(prefix, dest, { bucket = "shared" } = {}) {
    let best = null;
    for await (const e of this.list(prefix, { bucket })) {
      if (!best || e.modified > best.modified || (e.modified === best.modified && e.key > best.key)) best = e;
    }
    if (!best) return null;
    return this.getFile(best.key, dest, { bucket });
  }

  // ---------- sync ----------

  sync(folder, o = {}) {
    return require("./sync")(this, folder, o);
  }

  // ---------- change feed ----------

  /** Changes after `since`, all pages; iterate it, then read .cursor. Without since: none, and .cursor is "now". */
  changes(o = {}) {
    return new ChangeFeed(this, o);
  }

  /**
   * Call cb(change) for every new change, in the background: on the push topic when the server has one,
   * else by long poll. Returns {stop(), ready, cursor}; `ready` resolves once the feed is followed.
   * o: {since, onError(err), onResync(cursor) (after CursorExpired: do a full listing), push: false}
   */
  onChange(cb, o = {}) {
    const st = { stopped: false, cursor: o.since ?? null, ctrls: new Set(), wakers: new Set() };
    let readyResolve;
    const ready = new Promise((r) => (readyResolve = r));
    const handle = {
      ready,
      get cursor() {
        return st.cursor;
      },
      stop() {
        st.stopped = true;
        for (const c of st.ctrls) c.abort();
        for (const w of st.wakers) w();
        readyResolve();
      },
    };
    const report = (e) => {
      if (st.stopped || !o.onError) return;
      try {
        o.onError(e);
      } catch {}
    };
    const nap = (ms) =>
      new Promise((r) => {
        const done = () => {
          clearTimeout(t);
          st.wakers.delete(done);
          r();
        };
        const t = setTimeout(done, ms);
        st.wakers.add(done);
      });
    const withSignal = async (fn) => {
      const ctrl = new AbortController();
      st.ctrls.add(ctrl);
      try {
        return await fn(ctrl.signal);
      } finally {
        st.ctrls.delete(ctrl);
      }
    };
    // One pass over the feed from the cursor; true when it worked.
    const drain = async (wait) => {
      try {
        await withSignal(async (signal) => {
          const feed = this.changes({ since: st.cursor, wait, signal });
          for await (const c of feed) {
            if (st.stopped) return;
            try {
              await cb(c);
            } catch (e) {
              report(e);
            }
          }
          if (!st.stopped) st.cursor = feed.cursor;
        });
        return true;
      } catch (e) {
        if (st.stopped) return false;
        if (e instanceof CursorExpired) {
          try {
            if (o.onResync) await o.onResync(e.cursor);
          } catch (err) {
            report(err);
          }
          st.cursor = e.cursor;
          return true;
        }
        report(e);
        await nap(5000);
        return false;
      }
    };
    const pollFor = async (ms) => {
      const until = Date.now() + ms;
      while (!st.stopped && Date.now() < until) await drain(25);
    };
    const followPush = async (push) => {
      let running = null;
      let again = false;
      const trigger = () => {
        if (running) {
          again = true;
          return;
        }
        running = (async () => {
          do {
            again = false;
            await drain(0);
          } while (again && !st.stopped);
          running = null;
        })();
      };
      try {
        await withSignal(async (signal) => {
          const res = await fetch(`${String(push.url).replace(/\/+$/, "")}/${encode(push.topic)}/json`, { signal });
          if (!res.ok || !res.body) throw new Error(`push answered ${res.status}`);
          trigger(); // whatever happened before the stream opened
          let buf = "";
          const dec = new TextDecoder();
          for await (const chunk of res.body) {
            buf += dec.decode(chunk, { stream: true });
            let i;
            while ((i = buf.indexOf("\n")) >= 0) {
              const line = buf.slice(0, i).trim();
              buf = buf.slice(i + 1);
              if (!line) continue;
              try {
                if (JSON.parse(line).event === "message") trigger();
              } catch {}
            }
          }
        });
      } catch {}
      if (running) await running;
    };

    (async () => {
      try {
        await this._ensure("changes");
      } catch (e) {
        report(e);
        return handle.stop();
      }
      while (!st.stopped && st.cursor == null) {
        try {
          await withSignal(async (signal) => {
            const feed = this.changes({ signal });
            for await (const _ of feed); // eslint-disable-line no-unused-vars
            st.cursor = feed.cursor;
          });
        } catch (e) {
          report(e);
          await nap(5000);
        }
      }
      readyResolve();
      while (!st.stopped) {
        let push = null;
        if (o.push !== false && this.features.has("push")) {
          try {
            push = (await this.info({ fresh: true })).push;
          } catch {}
        }
        if (push && push.url && push.topic) {
          await followPush(push);
          await pollFor(30 * 1000); // push dropped: poll a while, then try it again
        } else {
          await pollFor(5 * 60 * 1000);
        }
      }
    })();
    return handle;
  }

  // ---------- releases ----------

  /** The key-less publish URL, for electron-updater's generic provider; null when the server has none. */
  async releasesUrl() {
    await this._ensure("publish");
    const info = await this.info();
    return (info.publish && info.publish.url) || null;
  }

  /**
   * Publish `file` as release `version`: releases/<version>/<name>, then releases/latest.json (section 6).
   * extra: electron-builder's latest*.yml (written at the top of releases/, pointed at <version>/), .blockmap
   * files (next to the installer) and any other files to put at the top of releases/.
   */
  async publish(file, version, { notes, platform, extra = [] } = {}) {
    await this._ensure("files");
    version = String(version);
    if (!VERSION.test(version)) throw new PocketError("A version is 1-64 of A-Z a-z 0-9 . _ + -", "BadRequest");
    const name = path.basename(file);
    const relPath = `${version}/${name}`;
    const { sha256, size } = await fileHash(file);
    await this.putFile(`releases/${relPath}`, file);
    const ymls = [];
    for (const x of extra) {
      const n = path.basename(x);
      if (/^latest.*\.yml$/i.test(n)) ymls.push(x);
      else if (n.endsWith(".blockmap")) await this.putFile(`releases/${version}/${n}`, x);
      else await this.putFile(`releases/${n}`, x);
    }
    const mine = { name, path: relPath, size, sha256 };
    if (platform) mine.platform = platform;
    let doc;
    for (let i = 0; ; i++) {
      let old = null;
      let etag = null;
      try {
        const res = await this._request("GET", this._filePath("shared", "releases/latest.json"), { retry: true });
        etag = res.headers.get("etag");
        old = JSON.parse(await res.text());
      } catch (e) {
        if (!(e instanceof NotFound && e.code === "NoSuchKey") && !(e instanceof SyntaxError)) throw e;
      }
      const same = old && old.version === version && Array.isArray(old.files);
      doc = {
        app: this.name,
        version,
        published: Math.floor(Date.now() / 1000),
        notes: notes ?? (same ? old.notes || "" : ""),
        // another platform's build of the same version joins the release
        files: same ? old.files.filter((f) => f.name !== name).concat(mine) : [mine],
      };
      try {
        await this.put("releases/latest.json", JSON.stringify(doc, null, 2), {
          contentType: "application/json",
          ...(etag ? { ifMatch: etag } : { ifNoneMatch: "*" }),
        });
        break;
      } catch (e) {
        if (!(e instanceof PreconditionFailed) || i >= 20) throw e;
      }
    }
    for (const y of ymls) {
      const text = rewriteLatestYml(fs.readFileSync(y, "utf8"), version);
      await this.put(`releases/${path.basename(y)}`, text, { contentType: "text/yaml" });
    }
    let base = null;
    if (this.features.has("publish")) {
      try {
        const p = (await this.info()).publish;
        if (p && p.enabled) base = p.url || null;
      } catch {}
    }
    return this._release(doc, doc.files, base);
  }

  releases() {
    return { latest: ({ platform } = {}) => this._latestRelease(platform) };
  }

  async _latestRelease(platform) {
    await this._ensure("files");
    let doc = null;
    let base = null;
    if (this.features.has("publish")) {
      try {
        const p = (await this.info()).publish;
        if (p && p.enabled && p.url) base = p.url;
      } catch {}
    }
    if (base) {
      try {
        const res = await fetch(base + "latest.json", { signal: AbortSignal.timeout(this.timeoutMs) });
        if (res.status === 200) doc = await res.json();
      } catch {}
    }
    if (!doc) doc = await this.getJson("releases/latest.json", { default: null });
    if (!doc) return null;
    let files = Array.isArray(doc.files) ? doc.files : [];
    if (platform) {
      files = files.filter((f) => !f.platform || f.platform === "any" || f.platform === platform);
      if (!files.length) return null;
    }
    return this._release(doc, files, base);
  }

  _release(doc, files, base) {
    const rel = {
      app: doc.app,
      version: doc.version,
      published: doc.published,
      notes: doc.notes || "",
      files: files.map((f) => ({
        name: f.name,
        path: f.path,
        size: f.size,
        sha256: f.sha256,
        platform: f.platform ?? null,
        url: base ? base + encodeKey(f.path) : null,
      })),
    };
    Object.defineProperty(rel, "download", {
      enumerable: false,
      value: (dest, o) => this._download(rel, dest, o),
    });
    return rel;
  }

  async _download(rel, dest, { name } = {}) {
    const f = name ? rel.files.find((x) => x.name === name) : rel.files.length === 1 ? rel.files[0] : null;
    if (!f) {
      throw new PocketError(
        name ? `Release ${rel.version} has no file ${name}.` : `Release ${rel.version} has ${rel.files.length} files: pass {name}.`,
        "BadRequest"
      );
    }
    let target = path.resolve(dest);
    try {
      if (fs.statSync(target).isDirectory()) target = path.join(target, f.name);
    } catch {}
    let body = null;
    if (f.url) {
      try {
        const res = await fetch(f.url, { signal: AbortSignal.timeout(this.timeoutMs) });
        if (res.status === 200) body = res.body;
      } catch {}
    }
    if (!body) body = (await this._request("GET", this._filePath("shared", `releases/${f.path}`), { retry: true })).body;
    fs.mkdirSync(path.dirname(target), { recursive: true });
    const tmp = tmpBeside(target);
    const sha = crypto.createHash("sha256");
    let size = 0;
    const count = new Transform({
      transform(chunk, _enc, done) {
        sha.update(chunk);
        size += chunk.length;
        done(null, chunk);
      },
    });
    try {
      await pipeline(Readable.fromWeb(body), count, fs.createWriteStream(tmp));
    } catch (e) {
      fs.rmSync(tmp, { force: true });
      throw new Unreachable(`The download of ${f.name} broke off: ${e.message}`, "Unreachable");
    }
    const got = sha.digest("hex");
    if (size !== f.size || got !== f.sha256) {
      fs.rmSync(tmp, { force: true });
      throw new PocketError(`${f.name} did not match latest.json (size or sha256); refused.`, "ChecksumMismatch");
    }
    fs.renameSync(tmp, target);
    return target;
  }

  // ---------- notify, share, quota ----------

  async notify(message, { title, tags, priority, click } = {}) {
    await this._ensure("notify");
    const body = { message };
    if (title != null) body.title = title;
    if (tags != null) body.tags = tags;
    if (priority != null) body.priority = priority;
    if (click != null) body.click = click;
    const res = await this._request("POST", "/_pocket/notify", {
      body: JSON.stringify(body),
      headers: { "content-type": "application/json" },
    });
    return res.json();
  }

  async share(key, { expires = 86400, bucket = "shared", password, maxDownloads } = {}) {
    await this._ensure("share");
    const body = { bucket, key, expires_s: expires };
    if (password) body.password = password;
    if (maxDownloads != null) body.max_downloads = maxDownloads;
    const res = await this._request("POST", "/_pocket/share", {
      body: JSON.stringify(body),
      headers: { "content-type": "application/json" },
    });
    return res.json();
  }

  async quota() {
    await this._ensure("quota");
    return (await this._request("GET", "/_pocket/quota", { retry: true })).json();
  }
}

/** pocket.app(name, opts), given how index.js makes a Device. */
function makeApp(newDevice) {
  return async function app(name, opts = {}) {
    const dev = newDevice(name, opts);
    if (!dev.creds) await dev.enroll();
    const a = new PocketApp(dev, opts);
    try {
      await a._loadFeatures();
    } catch (e) {
      if (!(e instanceof Unreachable)) throw e; // offline: features are fetched on the first call
    }
    return a;
  };
}

module.exports = { makeApp, PocketApp, rewriteLatestYml, fileHash };
