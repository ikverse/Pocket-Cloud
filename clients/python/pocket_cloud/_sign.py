"""Request signing for the /_pocket/ routes (a port of the server's sigv4.sign_pocket) and key encoding."""

import hashlib
import hmac

from ._errors import PocketError

EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
_SAFE = frozenset(b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~")


def quote(text):
    """Percent-encode every byte outside A-Z a-z 0-9 - _ . ~ (also '/')."""
    return "".join(chr(b) if b in _SAFE else "%%%02X" % b for b in text.encode("utf-8"))


def check_key(key):
    """Raise PocketError(BadKey) when key breaks the rules of docs/app-api.md section 1."""
    if not isinstance(key, str):
        raise PocketError("A key is a string.", "BadKey")
    raw = key.encode("utf-8")
    if not 1 <= len(raw) <= 1024:
        raise PocketError("A key is 1-1024 bytes of UTF-8.", "BadKey")
    if key.startswith("/") or key.endswith("/") or "\0" in key:
        raise PocketError(f"A key may not start or end with '/' or hold a NUL byte: {key!r}", "BadKey")
    for seg in key.split("/"):
        if seg in ("", ".", ".."):
            raise PocketError(f"A key may not have an empty, '.' or '..' segment: {key!r}", "BadKey")
    return key


def key_path(key):
    """A key as it goes in a path: each segment percent-encoded, '/' kept."""
    check_key(key)
    return "/".join(quote(seg) for seg in key.split("/"))


def query_string(params):
    """The raw query string for a list of (name, value) pairs, None values left out, order kept."""
    return "&".join(f"{quote(str(k))}={quote(str(v))}" for k, v in params if v is not None)


def string_to_sign(method, raw_path, raw_query, date, body_sha256):
    return "\n".join([method.upper(), raw_path, raw_query or "", str(int(date)), body_sha256])


def sign(method, raw_path, raw_query, body_sha256, access_key, secret, date):
    """The three x-pocket-* headers for a request whose body hashes to body_sha256 (lower-case hex)."""
    sig = hmac.new(secret.encode("utf-8"),
                   string_to_sign(method, raw_path, raw_query, date, body_sha256).encode("utf-8"),
                   hashlib.sha256).hexdigest()
    return {"x-pocket-key": access_key, "x-pocket-date": str(int(date)), "x-pocket-sign": sig}
