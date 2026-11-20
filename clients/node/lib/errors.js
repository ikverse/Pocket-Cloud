"use strict";
// The error types shared by the three Pocket Cloud libraries (docs/app-api.md section 10).

class PocketError extends Error {
  /**
   * @param {string} message
   * @param {string} [code] the server's "error" code or a library code
   * @param {number} [status] HTTP status, 0 when there was no answer
   */
  constructor(message, code = "PocketError", status = 0) {
    super(message);
    this.name = new.target.name;
    this.code = code;
    this.status = status;
  }
}

class NotFound extends PocketError {}
class PreconditionFailed extends PocketError {}
class QuotaExceeded extends PocketError {}
class ReadOnly extends PocketError {}
class AccessDenied extends PocketError {}
class CursorExpired extends PocketError {
  constructor(message, code, status, cursor) {
    super(message, code, status);
    this.cursor = cursor;
  }
}
class RateLimited extends PocketError {
  constructor(message, code, status, retryAfter) {
    super(message, code, status);
    this.retryAfter = retryAfter;
  }
}
class FeatureOff extends PocketError {}
class Unreachable extends PocketError {}

const BY_CODE = {
  NoSuchKey: NotFound,
  NoSuchBucket: NotFound,
  NotFound: NotFound,
  PreconditionFailed: PreconditionFailed,
  KeyExists: PreconditionFailed,
  QuotaExceeded: QuotaExceeded,
  PoolFull: QuotaExceeded,
  PhoneStorageLow: QuotaExceeded,
  ReadOnly: ReadOnly,
  AccessDenied: AccessDenied,
  InvalidAccessKeyId: AccessDenied,
  SignatureDoesNotMatch: AccessDenied,
  MissingSignature: AccessDenied,
  RequestTimeTooSkewed: AccessDenied,
  CursorExpired: CursorExpired,
  RateLimited: RateLimited,
  FeatureOff: FeatureOff,
  FeatureMissing: FeatureOff,
  SharesOff: FeatureOff,
  ServiceUnavailable: Unreachable,
  Unreachable: Unreachable,
};

/** The error for a server answer: status, the "error" code (or the HEAD header), the JSON body and headers. */
function fromResponse(status, code, body, headers) {
  code = code || (status === 404 ? "NotFound" : status === 503 ? "ServiceUnavailable" : "PocketError");
  const message = (body && body.message) || `Pocket Cloud answered ${status} ${code}.`;
  const Cls = BY_CODE[code] || (status === 403 ? AccessDenied : PocketError);
  if (Cls === CursorExpired) return new CursorExpired(message, code, status, body && body.cursor != null ? String(body.cursor) : null);
  if (Cls === RateLimited) return new RateLimited(message, code, status, retryAfterOf(headers));
  return new Cls(message, code, status);
}

function retryAfterOf(headers) {
  const v = headers && headers.get && headers.get("retry-after");
  const n = v == null ? NaN : Number(v);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

module.exports = {
  PocketError,
  NotFound,
  PreconditionFailed,
  QuotaExceeded,
  ReadOnly,
  AccessDenied,
  CursorExpired,
  RateLimited,
  FeatureOff,
  Unreachable,
  fromResponse,
  retryAfterOf,
};
