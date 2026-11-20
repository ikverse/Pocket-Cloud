"use strict";
// Two-way sync between a local folder and a prefix in a bucket (docs/app-api.md section 10, "sync rules").

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { PocketError, NotFound, PreconditionFailed, CursorExpired } = require("./errors");

const STATE = ".pocket-sync.json";
const MASS_DELETE = 50;

const skipName = (n) => n.startsWith(".pocket-");

function scanLocal(root) {
  const out = {};
  const walk = (dir, rel) => {
    for (const d of fs.readdirSync(dir, { withFileTypes: true })) {
      if (skipName(d.name)) continue;
      const abs = path.join(dir, d.name);
      const r = rel ? `${rel}/${d.name}` : d.name;
      if (d.isDirectory()) walk(abs, r);
      else if (d.isFile()) {
        const st = fs.statSync(abs);
        out[r] = { size: st.size, mtime: st.mtimeMs };
      }
    }
  };
  walk(root, "");
  return out;
}

function md5Quoted(file) {
  return `"${crypto.createHash("md5").update(fs.readFileSync(file)).digest("hex")}"`;
}

function pad(n) {
  return String(n).padStart(2, "0");
}

function conflictName(rel, deviceName) {
  const d = new Date();
  const when = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}${pad(d.getMinutes())}`;
  const dev = String(deviceName).replace(/[\\/:*?"<>|]/g, "-");
  const dir = path.posix.dirname(rel);
  const ext = path.posix.extname(rel);
  const base = path.posix.basename(rel, ext);
  const name = `${base} (conflict ${dev} ${when})${ext}`;
  return dir === "." ? name : `${dir}/${name}`;
}

/** The remote side as rel -> {etag, size}, by a full listing. */
async function listRemote(app, bucket, prefix) {
  const out = {};
  for await (const e of app.list(prefix, { bucket })) {
    const rel = e.key.slice(prefix.length);
    if (usable(rel)) out[rel] = { etag: e.etag, size: e.size };
  }
  return out;
}

const usable = (rel) => rel && !rel.split("/").some((s) => !s || s === "." || s === ".." || skipName(s));

module.exports = async function sync(app, folder, o = {}) {
  const bucket = o.bucket || "shared";
  const prefix = o.prefix || "";
  const allowMassDelete = !!o.allowMassDelete;
  await app._ensure("files");
  const root = path.resolve(folder);
  fs.mkdirSync(root, { recursive: true });
  const stateFile = path.join(root, STATE);

  let state = null;
  try {
    state = JSON.parse(fs.readFileSync(stateFile, "utf8"));
    if (!state || state.bucket !== bucket || state.prefix !== prefix || typeof state.files !== "object") state = null;
  } catch {}
  const prev = state ? state.files : {};
  const first = !state;
  const useFeed = app.features.has("changes");

  // ---- the remote side now ----
  let remote = null;
  let cursor = null;
  if (!first && useFeed && state.cursor) {
    try {
      remote = {};
      for (const [rel, s] of Object.entries(prev)) remote[rel] = { etag: s.etag, size: s.rsize ?? null };
      const feed = app.changes({ since: state.cursor });
      for await (const c of feed) {
        if (c.kind === "move" && c.from && c.from.bucket === bucket && c.from.key.startsWith(prefix)) {
          delete remote[c.from.key.slice(prefix.length)];
        }
        if (c.bucket !== bucket || !c.key.startsWith(prefix)) continue;
        const rel = c.key.slice(prefix.length);
        if (!usable(rel)) continue;
        if (c.kind === "delete") delete remote[rel];
        else remote[rel] = { etag: c.etag, size: c.size };
      }
      cursor = feed.cursor;
    } catch (e) {
      if (!(e instanceof CursorExpired)) throw e;
      cursor = e.cursor;
      remote = null;
    }
  } else if (useFeed) {
    const feed = app.changes();
    for await (const _ of feed); // eslint-disable-line no-unused-vars
    cursor = feed.cursor;
  }
  if (!remote) remote = await listRemote(app, bucket, prefix);

  const local = scanLocal(root);
  const result = { uploaded: [], downloaded: [], deletedLocal: [], deletedRemote: [], conflicts: [] };
  const next = {};
  const abs = (rel) => path.join(root, ...rel.split("/"));
  const keyOf = (rel) => prefix + rel;

  const record = (rel, etag, size) => {
    const st = fs.statSync(abs(rel));
    next[rel] = { size: st.size, mtime: st.mtimeMs, etag, rsize: size ?? st.size };
  };
  const download = async (rel) => {
    try {
      const e = await app.getFile(keyOf(rel), abs(rel), { bucket });
      record(rel, e.etag, e.size);
      result.downloaded.push(keyOf(rel));
    } catch (e) {
      if (!(e instanceof NotFound)) throw e; // gone meanwhile: nothing to fetch
    }
  };
  const conflict = async (rel) => {
    const cRel = conflictName(rel, app.dev.deviceName);
    fs.renameSync(abs(rel), abs(cRel));
    const e = await app.putFile(keyOf(cRel), abs(cRel), { bucket, ifNoneMatch: "*" });
    record(cRel, e.etag, e.size);
    result.conflicts.push(keyOf(rel), keyOf(cRel));
    await download(rel);
  };
  const upload = async (rel, cond) => {
    try {
      const e = await app.putFile(keyOf(rel), abs(rel), { bucket, ...cond });
      record(rel, e.etag, e.size);
      result.uploaded.push(keyOf(rel));
    } catch (e) {
      if (!(e instanceof PreconditionFailed)) throw e;
      await conflict(rel); // changed on the server meanwhile
    }
  };
  const deleteLocal = async (rel) => {
    fs.rmSync(abs(rel), { force: true });
    result.deletedLocal.push(keyOf(rel));
  };
  const deleteRemote = async (rel, etag) => {
    try {
      await app.delete(keyOf(rel), { bucket, ifMatch: etag || undefined });
      result.deletedRemote.push(keyOf(rel));
    } catch (e) {
      if (e instanceof PreconditionFailed) return download(rel); // changed on the server: keep it
      if (!(e instanceof NotFound)) throw e;
    }
  };
  const sameContent = (rel) => remote[rel].etag === md5Quoted(abs(rel));

  // ---- decide ----
  const actions = []; // [kind, rel, arg]
  const rels = [...new Set([...Object.keys(local), ...Object.keys(remote), ...Object.keys(prev)])].sort();
  for (const rel of rels) {
    const L = local[rel];
    const R = remote[rel];
    const S = first ? null : prev[rel];
    const lChanged = L && (!S || L.size !== S.size || L.mtime !== S.mtime);
    const rChanged = R && (!S || R.etag !== S.etag);
    if (L && R) {
      if (!lChanged && !rChanged) next[rel] = { ...S };
      else if (lChanged && rChanged) actions.push(sameContent(rel) ? ["same", rel] : ["conflict", rel]);
      else if (lChanged) actions.push(["upload", rel, { ifMatch: R.etag }]);
      else actions.push(["download", rel]);
    } else if (L) {
      if (S && !lChanged) actions.push(["deleteLocal", rel]);
      else actions.push(["upload", rel, { ifNoneMatch: "*" }]);
    } else if (R) {
      if (S && !rChanged) actions.push(["deleteRemote", rel, R.etag]);
      else actions.push(["download", rel]);
    }
    // neither side has it any more: it drops out of the state
  }
  const deletes = actions.filter(([k]) => k === "deleteLocal" || k === "deleteRemote");
  const held = deletes.length > MASS_DELETE && !allowMassDelete;

  // ---- do ----
  for (const [kind, rel, arg] of actions) {
    if (kind === "same") record(rel, remote[rel].etag, remote[rel].size);
    else if (kind === "conflict") await conflict(rel);
    else if (kind === "upload") await upload(rel, arg);
    else if (kind === "download") await download(rel);
    else if (held) next[rel] = { ...prev[rel] }; // asked again next run
    else if (kind === "deleteLocal") await deleteLocal(rel);
    else if (kind === "deleteRemote") await deleteRemote(rel, arg);
  }

  const tmp = path.join(root, `.pocket-sync-${crypto.randomBytes(4).toString("hex")}.tmp`);
  fs.writeFileSync(tmp, JSON.stringify({ version: 1, bucket, prefix, cursor, files: next }, null, 1));
  fs.renameSync(tmp, stateFile);

  if (held) {
    const err = new PocketError(
      `This sync would delete ${deletes.length} files (more than ${MASS_DELETE}); nothing was deleted. ` +
        "Pass allowMassDelete: true if that is meant.",
      "MassDelete"
    );
    err.count = deletes.length;
    err.result = result;
    throw err;
  }
  return result;
};

module.exports.conflictName = conflictName;
