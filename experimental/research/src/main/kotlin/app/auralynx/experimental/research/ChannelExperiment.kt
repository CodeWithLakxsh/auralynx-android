package app.auralynx.experimental.research

import android.os.Build
import app.auralynx.core.bluetooth.aap.AapSession
import app.auralynx.core.bluetooth.aap.AapTransport
import app.auralynx.core.bluetooth.aap.ChannelError
import app.auralynx.core.common.ApplicationScope
import app.auralynx.core.common.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Reproducible rootless-compatibility experiment (see docs/research/ROOTLESS_COMPATIBILITY.md).
 *
 * Hypothesis H1: on this phone, an app can open the AAP channel using only public APIs.
 * Hypothesis H2: once open, the accessory answers the documented initialisation sequence.
 * Hypothesis H3: the accessory accepts Adaptive mode without Apple's Device ID (observed by echo).
 *
 * The experiment does not change any persistent accessory setting except, for H3, the listening
 * mode, which it restores afterwards.
 */
class ChannelExperiment @Inject constructor(
    private val transport: AapTransport,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    data class Step(val name: String, val outcome: String, val millis: Long)

    data class Report(
        val device: Map<String, String>,
        val steps: List<Step>,
        val h1ChannelOpened: Boolean?,
        val h2AccessoryResponded: Boolean?,
        val h3AdaptiveAccepted: Boolean?,
        val packetsReceived: Int,
        val firstOpcodes: List<String>,
    ) {
        fun toJson(): String = buildString {
            append("{\n  \"schema\": \"auralynx.channel-experiment/1\",\n  \"device\": {")
            append(device.entries.joinToString(",") { "\n    \"${it.key}\": \"${it.value.replace("\"", "'")}\"" })
            append("\n  },\n  \"hypotheses\": {\"H1_channel_opened\": $h1ChannelOpened, \"H2_accessory_responded\": $h2AccessoryResponded, \"H3_adaptive_accepted\": $h3AdaptiveAccepted},")
            append("\n  \"packets_received\": $packetsReceived,\n  \"first_opcodes\": [${firstOpcodes.joinToString(",") { "\"$it\"" }}],")
            append("\n  \"steps\": [")
            append(steps.joinToString(",") { "\n    {\"step\": \"${it.name}\", \"outcome\": \"${it.outcome.replace("\"", "'")}\", \"ms\": ${it.millis}}" })
            append("\n  ]\n}\n")
        }
    }

    fun environment(): Map<String, String> = mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "android_release" to Build.VERSION.RELEASE,
        "sdk_int" to Build.VERSION.SDK_INT.toString(),
        "build_id" to Build.ID,
        "security_patch" to Build.VERSION.SECURITY_PATCH,
        "transport_os_support" to transport.osSupport().name,
        "transport_probe" to transport.supportDetail(),
    )

    /** Runs the experiment against an accessory that is already connected over A2DP/HFP. */
    suspend fun run(address: String, testAdaptive: Boolean): Report {
        val steps = mutableListOf<Step>()
        fun step(name: String, start: Long, outcome: String) { steps += Step(name, outcome, clock.nowMillis() - start) }

        if (transport.osSupport() == AapTransport.OsSupport.UNSUPPORTED) {
            steps += Step("os_check", "Public classic-L2CAP API not available on SDK ${Build.VERSION.SDK_INT}", 0)
            return Report(environment(), steps, false, null, null, 0, emptyList())
        }
        var t = clock.nowMillis()
        val connection = try {
            transport.connect(address).also { step("open_channel", t, "connected") }
        } catch (e: ChannelError) {
            step("open_channel", t, "${e::class.simpleName}: ${e.message}")
            return Report(environment(), steps, false, null, null, 0, emptyList())
        }
        val session = AapSession(connection, scope, clock)
        try {
            t = clock.nowMillis()
            session.start()
            val responded = withTimeoutOrNull(4_000) { session.state.first { it.packetsReceived > 0 } } != null
            step("initialise", t, if (responded) "accessory responded" else "no response within 4 s")
            delay(1_500) // collect the initial notification burst
            var adaptive: Boolean? = null
            if (testAdaptive && responded) {
                val previous = session.state.value.listeningMode
                t = clock.nowMillis()
                val result = session.setListeningMode(app.auralynx.core.protocol.model.ListeningMode.ADAPTIVE)
                adaptive = result is app.auralynx.core.common.Outcome.Success
                step("adaptive_mode", t, result.toString())
                previous?.let { session.setListeningMode(it) }
            }
            val opcodes = session.packetLog.value.filter { !it.outgoing }.mapNotNull { it.opcode }.distinct().take(20).map { "0x%04X".format(it) }
            return Report(environment(), steps, true, responded, adaptive, session.state.value.packetsReceived, opcodes)
        } finally {
            session.close()
        }
    }
}
