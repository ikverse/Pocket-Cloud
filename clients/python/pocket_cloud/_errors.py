"""The errors every Pocket Cloud library raises (docs/app-api.md, section 10)."""


class PocketError(Exception):
    """Pocket Cloud could not be reached or refused this app or device.

    .code is the server's stable "error" code or a library code (FeatureMissing, MassDelete, ChecksumMismatch,
    NotConfigured, Unreachable); .status is the HTTP status, or 0 when the library raised it itself."""

    def __init__(self, message="", code=None, status=0):
        super().__init__(message)
        self.message = message
        self.code = code or "PocketError"
        self.status = status


class NotFound(PocketError):
    """NoSuchKey, NoSuchBucket, NotFound."""


class PreconditionFailed(PocketError):
    """if-match / if-none-match did not hold, or a move target exists (KeyExists)."""


class QuotaExceeded(PocketError):
    """Over the app's allocation or the device cap, or no room on the phone."""


class ReadOnly(PocketError):
    """The server is in read-only mode."""


class AccessDenied(PocketError):
    """The key or its signature was refused, the device is blocked or the app waits for approval."""


class CursorExpired(PocketError):
    """A change-feed cursor is older than what the server keeps. .cursor is where to carry on after a resync."""

    def __init__(self, message="", code="CursorExpired", status=410, cursor=None):
        super().__init__(message, code, status)
        self.cursor = cursor


class RateLimited(PocketError):
    """Too many notifications. .retry_after is the wait in seconds."""

    def __init__(self, message="", code="RateLimited", status=429, retry_after=None):
        super().__init__(message, code, status)
        self.retry_after = retry_after


class FeatureOff(PocketError):
    """The feature is switched off for this app (FeatureOff), or the server does not have it (FeatureMissing)."""


class Unreachable(PocketError):
    """The server could not be reached, timed out, or kept answering ServiceUnavailable."""


_BY_CODE = {
    "NoSuchKey": NotFound, "NoSuchBucket": NotFound, "NotFound": NotFound,
    "PreconditionFailed": PreconditionFailed, "KeyExists": PreconditionFailed,
    "QuotaExceeded": QuotaExceeded, "PoolFull": QuotaExceeded, "PhoneStorageLow": QuotaExceeded,
    "ReadOnly": ReadOnly,
    "AccessDenied": AccessDenied, "InvalidAccessKeyId": AccessDenied, "SignatureDoesNotMatch": AccessDenied,
    "MissingSignature": AccessDenied, "RequestTimeTooSkewed": AccessDenied, "Forbidden": AccessDenied,
    "AppWaiting": AccessDenied,
    "CursorExpired": CursorExpired,
    "RateLimited": RateLimited,
    "FeatureOff": FeatureOff, "FeatureMissing": FeatureOff, "SharesOff": FeatureOff,
    "ServiceUnavailable": Unreachable, "Unreachable": Unreachable,
}

_BY_STATUS = {404: NotFound, 409: PreconditionFailed, 412: PreconditionFailed, 403: AccessDenied,
              410: CursorExpired, 429: RateLimited, 503: Unreachable, 507: QuotaExceeded}


def error_for(status, code, message, body=None, headers=None):
    """The library error for a server answer."""
    cls = _BY_CODE.get(code) or _BY_STATUS.get(status) or PocketError
    message = message or f"Pocket Cloud answered {status} {code or ''}".strip()
    if cls is CursorExpired:
        return CursorExpired(message, code or "CursorExpired", status, cursor=(body or {}).get("cursor"))
    if cls is RateLimited:
        retry = None
        try:
            retry = float((headers or {}).get("retry-after"))
        except (TypeError, ValueError):
            pass
        return RateLimited(message, code or "RateLimited", status, retry_after=retry)
    return cls(message, code or cls.__name__, status)
