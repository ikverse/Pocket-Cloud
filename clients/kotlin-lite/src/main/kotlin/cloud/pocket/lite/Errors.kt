package cloud.pocket.lite

import java.io.IOException

/**
 * Everything the library throws. [code] is the server's stable `error` code or a library code
 * (`FeatureMissing`, `MassDelete`, `ChecksumMismatch`, `NotConfigured`, `Unreachable`); [status] is the HTTP
 * status, or 0 when there was no answer.
 */
open class PocketException(val code: String, val status: Int, message: String, cause: Throwable? = null) :
    IOException(message, cause)

class NotFoundException(code: String, status: Int, message: String) : PocketException(code, status, message)
class PreconditionFailedException(code: String, status: Int, message: String) : PocketException(code, status, message)
class QuotaExceededException(code: String, status: Int, message: String) : PocketException(code, status, message)
class ReadOnlyException(code: String, status: Int, message: String) : PocketException(code, status, message)
class AccessDeniedException(code: String, status: Int, message: String) : PocketException(code, status, message)

/** The change-feed cursor is too old. Do a full resync, then carry on from [cursor]. */
class CursorExpiredException(code: String, status: Int, message: String, val cursor: String?) :
    PocketException(code, status, message)

/** Notify rate limit. Try again after [retryAfter] seconds. */
class RateLimitedException(code: String, status: Int, message: String, val retryAfter: Int?) :
    PocketException(code, status, message)

/** Switched off for this app (`FeatureOff`), or the server does not have it (`FeatureMissing`). */
class FeatureOffException(code: String, status: Int, message: String) : PocketException(code, status, message)

/** No connection, a timeout, or `ServiceUnavailable` after the retries. */
class UnreachableException(code: String, status: Int, message: String, cause: Throwable? = null) :
    PocketException(code, status, message, cause)

/** Build the right exception for a server error code. */
internal fun errorFor(code: String, status: Int, message: String, cursor: String? = null, retryAfter: Int? = null):
    PocketException = when (code) {
    "NoSuchKey", "NoSuchBucket", "NotFound" -> NotFoundException(code, status, message)
    "PreconditionFailed", "KeyExists" -> PreconditionFailedException(code, status, message)
    "QuotaExceeded", "PoolFull", "PhoneStorageLow" -> QuotaExceededException(code, status, message)
    "ReadOnly" -> ReadOnlyException(code, status, message)
    "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "MissingSignature", "RequestTimeTooSkewed",
    "Forbidden", "AppWaiting" -> AccessDeniedException(code, status, message)
    "CursorExpired" -> CursorExpiredException(code, status, message, cursor)
    "RateLimited" -> RateLimitedException(code, status, message, retryAfter)
    "FeatureOff", "FeatureMissing", "SharesOff" -> FeatureOffException(code, status, message)
    "ServiceUnavailable", "Unreachable" -> UnreachableException(code, status, message)
    else -> when (status) {
        403 -> AccessDeniedException(code, status, message)
        404 -> NotFoundException(code, status, message)
        412 -> PreconditionFailedException(code, status, message)
        507 -> QuotaExceededException(code, status, message)
        in 500..599 -> UnreachableException(code, status, message)
        else -> PocketException(code, status, message)
    }
}
