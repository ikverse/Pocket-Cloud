"""AWS Signature Version 4, only where the gateway needs it.

rclone checks the signature of every normal request. It refuses temporary links (query-string signatures)
when per-key isolation is on, so the gateway checks those itself and passes the request on to rclone as a
normal header-signed request made with the same key.
"""

import base64
import datetime
import hashlib
import hmac
import re
from urllib.parse import quote, unquote

ALGO = "AWS4-HMAC-SHA256"
UNSIGNED = "UNSIGNED-PAYLOAD"
MAX_EXPIRES = 7 * 24 * 3600
_CRED = re.compile(r"Credential=([^/,\s]+)/(\d{8})/([^/]+)/([^/]+)/aws4_request")
_SPACES = re.compile(r"\s+")


def access_key_from_header(authorization):
    if not authorization or not authorization.startswith(ALGO):
        return None
    m = _CRED.search(authorization)
    return m.group(1) if m else None


def parse_query(raw_query):
    """Raw query string -> list of (name, value), decoded, in original order."""
    pairs = []
    for part in raw_query.split("&") if raw_query else []:
        if not part:
            continue
        k, _, v = part.partition("=")
        pairs.append((unquote(k), unquote(v)))
    return pairs


def _enc(s):
    return quote(s, safe="-_.~")


def canonical_query(pairs):
    return "&".join(f"{_enc(k)}={_enc(v)}" for k, v in sorted(pairs))


def _hmac(key, msg):
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def signing_key(secret, date, region, service="s3"):
    k = _hmac(("AWS4" + secret).encode("utf-8"), date)
    k = _hmac(k, region)
    k = _hmac(k, service)
    return _hmac(k, "aws4_request")


def signature(secret, method, raw_path, query_pairs, headers, signed_headers, payload_hash, amz_date, region):
    """headers: dict of lowercase name -> value. signed_headers: list of lowercase names."""
    canon_headers = "".join(f"{h}:{_SPACES.sub(' ', headers.get(h, '').strip())}\n" for h in signed_headers)
    creq = "\n".join([method, raw_path or "/", canonical_query(query_pairs), canon_headers,
                      ";".join(signed_headers), payload_hash])
    scope = f"{amz_date[:8]}/{region}/s3/aws4_request"
    sts = "\n".join([ALGO, amz_date, scope, hashlib.sha256(creq.encode("utf-8")).hexdigest()])
    return hmac.new(signing_key(secret, amz_date[:8], region), sts.encode("utf-8"), hashlib.sha256).hexdigest()


def presigned_key(query_pairs):
    """Access key of a temporary link (signature version 4 or the older version 2), or None."""
    q = dict(query_pairs)
    if q.get("X-Amz-Algorithm") == ALGO and "X-Amz-Signature" in q:
        return q.get("X-Amz-Credential", "").split("/")[0] or None
    if "AWSAccessKeyId" in q and "Signature" in q and "Expires" in q:
        return q["AWSAccessKeyId"] or None
    return None


def verify_presigned_any(method, raw_path, query_pairs, headers, secret, now=None):
    if "X-Amz-Algorithm" in dict(query_pairs):
        return verify_presigned(method, raw_path, query_pairs, headers, secret, now)
    ok, why = verify_presigned_v2(method, raw_path, query_pairs, headers, secret, now)
    return ok, ("us-east-1" if ok else why)


# Signature version 2 links: still what boto3 makes by default unless told to use version 4.
_V2_SUBRESOURCES = {
    "acl", "cors", "delete", "lifecycle", "location", "logging", "notification", "partNumber", "policy",
    "requestPayment", "response-cache-control", "response-content-disposition", "response-content-encoding",
    "response-content-language", "response-content-type", "response-expires", "restore", "tagging", "torrent",
    "uploadId", "uploads", "versionId", "versioning", "versions", "website",
}


def verify_presigned_v2(method, raw_path, query_pairs, headers, secret, now=None):
    q = dict(query_pairs)
    try:
        expires = int(q["Expires"])
        given = q["Signature"]
    except (KeyError, ValueError):
        return False, "AuthorizationQueryParametersError"
    now_ts = (now or datetime.datetime.now(datetime.timezone.utc)).timestamp()
    if now_ts > expires:
        return False, "AccessDenied"
    amz = sorted((k.lower(), v.strip()) for k, v in headers.items() if k.lower().startswith("x-amz-"))
    amz_lines = "".join(f"{k}:{v}\n" for k, v in amz)
    subs = sorted((k, v) for k, v in query_pairs if k in _V2_SUBRESOURCES)
    resource = raw_path + ("?" + "&".join(k if v == "" else f"{k}={v}" for k, v in subs) if subs else "")
    sts = "\n".join([method, headers.get("content-md5", ""), headers.get("content-type", ""), str(expires)]) + "\n"
    sts += amz_lines + resource
    want = base64.b64encode(hmac.new(secret.encode("utf-8"), sts.encode("utf-8"), hashlib.sha1).digest()).decode()
    if not hmac.compare_digest(want, given):
        return False, "SignatureDoesNotMatch"
    return True, ""


def verify_presigned(method, raw_path, query_pairs, headers, secret, now=None):
    """Check a temporary link. Returns (ok, reason)."""
    q = dict(query_pairs)
    try:
        ak, date, region, service, term = q["X-Amz-Credential"].split("/")
        amz_date = q["X-Amz-Date"]
        expires = int(q["X-Amz-Expires"])
        signed_headers = q["X-Amz-SignedHeaders"].lower().split(";")
        given = q["X-Amz-Signature"]
    except (KeyError, ValueError):
        return False, "AuthorizationQueryParametersError"
    if service != "s3" or term != "aws4_request" or amz_date[:8] != date:
        return False, "AuthorizationQueryParametersError"
    if not 1 <= expires <= MAX_EXPIRES:
        return False, "AuthorizationQueryParametersError"
    try:
        t = datetime.datetime.strptime(amz_date, "%Y%m%dT%H%M%SZ").replace(tzinfo=datetime.timezone.utc)
    except ValueError:
        return False, "AuthorizationQueryParametersError"
    now = now or datetime.datetime.now(datetime.timezone.utc)
    if now > t + datetime.timedelta(seconds=expires):
        return False, "AccessDenied"         # "Request has expired"
    if now < t - datetime.timedelta(minutes=15):
        return False, "RequestTimeTooSkewed"
    pairs = [(k, v) for k, v in query_pairs if k != "X-Amz-Signature"]
    payload = headers.get("x-amz-content-sha256", UNSIGNED) if "x-amz-content-sha256" in signed_headers else UNSIGNED
    want = signature(secret, method, raw_path, pairs, headers, signed_headers, payload, amz_date, region)
    if not hmac.compare_digest(want, given):
        return False, "SignatureDoesNotMatch"
    return True, region


def strip_presign(query_pairs):
    return [(k, v) for k, v in query_pairs
            if not k.startswith("X-Amz-") and k not in ("AWSAccessKeyId", "Signature", "Expires")]


# Pocket Cloud's own endpoints (/_pocket/quota and friends) are signed with the device key too, but more simply
# than S3: three headers and one HMAC, so any client can do it in ten lines.
#
#     x-pocket-key:  <access key>
#     x-pocket-date: <unix seconds>  (within 15 minutes of the server's clock)
#     x-pocket-sign: hex(HMAC-SHA256(secret, "METHOD\nPATH\nQUERY\nDATE\nsha256hex(BODY)"))
#
# PATH is the request path (e.g. /_pocket/quota), QUERY the raw query string as sent ("" when none).
POCKET_SKEW = 15 * 60


def _pocket_string(method, raw_path, raw_query, date, body):
    return "\n".join([method.upper(), raw_path, raw_query or "", str(date), hashlib.sha256(body or b"").hexdigest()])


def sign_pocket(method, raw_path, raw_query, body, access_key, secret, now=None):
    """Headers for a request to a /_pocket/ endpoint (used by the tests and the client libraries)."""
    date = int(now if now is not None else datetime.datetime.now(datetime.timezone.utc).timestamp())
    sig = hmac.new(secret.encode("utf-8"), _pocket_string(method, raw_path, raw_query, date, body).encode("utf-8"),
                   hashlib.sha256).hexdigest()
    return {"x-pocket-key": access_key, "x-pocket-date": str(date), "x-pocket-sign": sig}


def verify_pocket(method, raw_path, raw_query, headers, body, secret, now=None):
    """Check a /_pocket/ request's headers (lower-cased names). Returns (ok, reason)."""
    try:
        date = int(headers.get("x-pocket-date", ""))
    except ValueError:
        return False, "MissingSignature"
    now = now if now is not None else datetime.datetime.now(datetime.timezone.utc).timestamp()
    if abs(now - date) > POCKET_SKEW:
        return False, "RequestTimeTooSkewed"
    want = hmac.new(secret.encode("utf-8"), _pocket_string(method, raw_path, raw_query, date, body).encode("utf-8"),
                    hashlib.sha256).hexdigest()
    if not hmac.compare_digest(want, headers.get("x-pocket-sign", "")):
        return False, "SignatureDoesNotMatch"
    return True, ""


def sign_headers(method, raw_path, query_pairs, host, access_key, secret, region="us-east-1", now=None, extra=None):
    """Header signature for a request whose body is not hashed. extra: other x-amz-* headers the request
    carries; they are signed too, as rclone refuses unsigned x-amz-* headers. Returns the headers to send."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    headers = {k.lower(): v for k, v in (extra or {}).items()}
    headers.update({"host": host, "x-amz-content-sha256": UNSIGNED, "x-amz-date": amz_date})
    signed = sorted(headers)
    sig = signature(secret, method, raw_path, query_pairs, headers, signed, UNSIGNED, amz_date, region)
    scope = f"{amz_date[:8]}/{region}/s3/aws4_request"
    headers["authorization"] = f"{ALGO} Credential={access_key}/{scope}, SignedHeaders={';'.join(signed)}, Signature={sig}"
    return headers
