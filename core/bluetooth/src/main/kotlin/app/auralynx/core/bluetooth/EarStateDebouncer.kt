package app.auralynx.core.bluetooth

/**
 * Turns noisy per-advertisement in-ear readings into a stable count of buds in ear.
 *
 * Each bud advertises independently and scan results can arrive late, so a single frame may
 * disagree with the true state. A new value is accepted only after [required] consecutive
 * readings agree. Readings older than the last accepted one are ignored.
 */
class EarStateDebouncer(private val required: Int = 2) {
    var stable: Int? = null
        private set
    var stableSinceMillis: Long = 0
        private set
    private var candidate: Int? = null
    private var candidateCount = 0
    private var lastTime = Long.MIN_VALUE

    /** Feeds one reading; returns true when the stable value changed. */
    fun offer(budsInEar: Int, timeMillis: Long): Boolean {
        if (timeMillis < lastTime) return false
        lastTime = timeMillis
        if (budsInEar == stable) { candidate = null; candidateCount = 0; return false }
        if (budsInEar == candidate) candidateCount++ else { candidate = budsInEar; candidateCount = 1 }
        if (candidateCount >= required) {
            stable = budsInEar; stableSinceMillis = timeMillis; candidate = null; candidateCount = 0
            return true
        }
        return false
    }
}
