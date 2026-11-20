"use strict";
// Signing and encoding for the /_pocket/ routes (docs/app-api.md section 1).

const crypto = require("crypto");

const EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

const sha256hex = (data) => crypto.createHash("sha256").update(data).digest("hex");

/** Percent-encode one path segment or query value: every byte outside A-Z a-z 0-9 - _ . ~ becomes %XX. */
function encode(s) {
  return encodeURIComponent(s).replace(/[!'()*]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase());
}

/** A key in a path: encoded per segment, "/" kept. */
const encodeKey = (key) => String(key).split("/").map(encode).join("/");

/** A raw query string from [name, value] pairs; pairs whose value is null or undefined are left out. */
function encodeQuery(pairs) {
  return pairs
    .filter(([, v]) => v !== undefined && v !== null)
    .map(([k, v]) => `${encode(k)}=${encode(String(v))}`)
    .join("&");
}

/**
 * The x-pocket-* headers for a request. path and query are exactly as sent on the wire; bodySha256 is the
 * hex SHA-256 of the body (the empty-string hash when there is none).
 */
function signPocket(method, path, query, bodySha256, accessKey, secret, date) {
  date = Math.floor(date ?? Date.now() / 1000);
  const text = [method.toUpperCase(), path, query || "", String(date), bodySha256 || EMPTY_SHA256].join("\n");
  const sign = crypto.createHmac("sha256", secret).update(text, "utf8").digest("hex");
  return { "x-pocket-key": accessKey, "x-pocket-date": String(date), "x-pocket-sign": sign };
}

module.exports = { EMPTY_SHA256, sha256hex, encode, encodeKey, encodeQuery, signPocket };
