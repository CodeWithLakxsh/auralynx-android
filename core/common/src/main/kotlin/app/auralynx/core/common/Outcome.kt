package app.auralynx.core.common

/** Result of an operation that can fail for a user-explainable reason. */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val reason: FailureReason, val cause: Throwable? = null) : Outcome<Nothing>
}

/** Failure categories surfaced in the UI. Each maps to an honest, user-facing explanation. */
enum class FailureReason {
    PERMISSION_MISSING,
    BLUETOOTH_OFF,
    NOT_CONNECTED,
    UNSUPPORTED_BY_ACCESSORY,
    UNSUPPORTED_BY_ANDROID,
    REQUIRES_PRIVILEGED_ACCESS,
    TIMEOUT,
    IO_ERROR,
    NOT_CONFIGURED,
    UNKNOWN,
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value
