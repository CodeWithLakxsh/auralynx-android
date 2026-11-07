package app.auralynx.core.common

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal structured diagnostic logger.
 *
 * Every message is passed through [Redaction.text] before it reaches any sink, so sinks never see
 * raw Bluetooth addresses or serial numbers. Nothing is transmitted anywhere: sinks are local
 * (logcat, the in-app ring buffer, the on-device database). Export requires explicit user action.
 */
object Diag {
    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    data class Entry(val timeMillis: Long, val level: Level, val tag: String, val message: String)

    fun interface Sink {
        fun write(entry: Entry)
    }

    private val sinks = CopyOnWriteArrayList<Sink>()

    @Volatile
    var minimumLevel: Level = Level.DEBUG

    fun addSink(sink: Sink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    fun log(level: Level, tag: String, throwable: Throwable? = null, message: () -> String) {
        if (level < minimumLevel || sinks.isEmpty()) return
        val text = buildString {
            append(message())
            if (throwable != null) append(" | ").append(throwable::class.java.simpleName).append(": ").append(throwable.message)
        }
        val entry = Entry(System.currentTimeMillis(), level, tag, Redaction.text(text))
        sinks.forEach { runCatching { it.write(entry) } }
    }

    fun d(tag: String, message: () -> String) = log(Level.DEBUG, tag, null, message)
    fun i(tag: String, message: () -> String) = log(Level.INFO, tag, null, message)
    fun w(tag: String, t: Throwable? = null, message: () -> String) = log(Level.WARN, tag, t, message)
    fun e(tag: String, t: Throwable? = null, message: () -> String) = log(Level.ERROR, tag, t, message)
}
