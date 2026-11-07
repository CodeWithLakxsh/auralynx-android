package app.auralynx.core.common

/** Injectable wall-clock so time-dependent logic (automation, analytics) is deterministic under test. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
