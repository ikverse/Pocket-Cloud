"use strict";
// The signing vectors of docs/app-api.md section 12, and the key / query encoding of section 1.

const { test } = require("node:test");
const assert = require("node:assert");
const crypto = require("crypto");
const { signPocket, sha256hex, encodeKey, encodeQuery, EMPTY_SHA256 } = require("../lib/sign");
const { verifyPocket } = require("./fake-server");

const KEY = "AKTEST0000000001";
const SECRET = "secret-for-tests-only";
const DATE = 1795000000;

const VECTORS = [
  ["GET", "/_pocket/quota", "", "", "d872ac21f923208dc0ca0344d8a3fe488e4b717b656d7328149e89799ac06b71"],
  ["PUT", "/_pocket/files/shared/notes/hello%20world.txt", "", "hello", "015bd2b75bedd7fbc3a9ce49abd670c4ff7a1ef6d8f96fd795cfbe5d8995c04f"],
  ["GET", "/_pocket/list", "bucket=shared&prefix=notes%2F&limit=2", "", "0884bcb1271e92c9f16e501b09205689aacc6e65c212461a42ffeb102f6f14d6"],
];

test("section 12 vectors", () => {
  for (const [method, path, query, body, want] of VECTORS) {
    const h = signPocket(method, path, query, sha256hex(body), KEY, SECRET, DATE);
    assert.deepStrictEqual(h, { "x-pocket-key": KEY, "x-pocket-date": String(DATE), "x-pocket-sign": want }, `${method} ${path}`);
  }
});

test("the fake's verifier accepts the vectors", () => {
  for (const [method, path, query, body, sign] of VECTORS) {
    const headers = { "x-pocket-key": KEY, "x-pocket-date": String(DATE), "x-pocket-sign": sign };
    assert.strictEqual(verifyPocket(method, path, query, headers, sha256hex(body), SECRET, DATE), "");
    assert.strictEqual(verifyPocket(method, path, query, headers, sha256hex(body + "!"), SECRET, DATE), "SignatureDoesNotMatch");
    assert.strictEqual(verifyPocket(method, path, query, headers, sha256hex(body), SECRET, DATE + 901), "RequestTimeTooSkewed");
  }
});

test("upload body hash and ETag of the PUT vector", () => {
  assert.strictEqual(sha256hex("hello"), "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
  assert.strictEqual(`"${crypto.createHash("md5").update("hello").digest("hex")}"`, '"5d41402abc4b2a76b9719d911017c592"');
  assert.strictEqual(sha256hex(""), EMPTY_SHA256);
});

test("keys are encoded per segment, queries as sent", () => {
  assert.strictEqual(encodeKey("notes/hello world.txt"), "notes/hello%20world.txt");
  assert.strictEqual(encodeKey("a/b-c_d.e~f/!'()*+&=é"), "a/b-c_d.e~f/%21%27%28%29%2A%2B%26%3D%C3%A9");
  assert.strictEqual(encodeQuery([["bucket", "shared"], ["prefix", "notes/"], ["cursor", null], ["limit", 2]]), "bucket=shared&prefix=notes%2F&limit=2");
});
