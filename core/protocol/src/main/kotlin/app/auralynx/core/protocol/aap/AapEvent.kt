package app.auralynx.core.protocol.aap

import app.auralynx.core.protocol.model.BatteryReading
import app.auralynx.core.protocol.model.Bud
import app.auralynx.core.protocol.model.BudPlacement
import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.core.protocol.model.PressType

/** Decoded packet received from the accessory. */
sealed interface AapEvent {
    data class Battery(val readings: List<BatteryReading>) : AapEvent

    /** Opcode 0x06. "Primary" is the bud currently carrying the microphone. */
    data class EarDetection(val primary: BudPlacement, val secondary: BudPlacement) : AapEvent

    data class ListeningModeChanged(val mode: ListeningMode) : AapEvent

    data class ListeningModeCycleChanged(val modes: Set<ListeningMode>) : AapEvent

    data class ConversationAwarenessSetting(val enabled: Boolean) : AapEvent

    data class AdaptiveNoiseLevel(val level: Int) : AapEvent

    /** Any control identifier Auralynx does not model explicitly. */
    data class ControlValue(val identifier: Int, val value: ByteArray) : AapEvent {
        override fun equals(other: Any?): Boolean =
            other is ControlValue && other.identifier == identifier && other.value.contentEquals(value)

        override fun hashCode(): Int = 31 * identifier + value.contentHashCode()
    }

    /**
     * Opcode 0x4B: the wearer started/stopped speaking. Levels 1-2 mean speech started (reduce
     * volume), 3 means speech stopped, 8-9 mean normal volume; intermediate values are ramp steps.
     */
    data class ConversationActivity(val level: Int) : AapEvent {
        val speaking: Boolean get() = level in 1..2
    }

    data class DeviceInfo(
        val name: String,
        val modelNumber: String,
        val manufacturer: String,
        val serialNumber: String,
        val firmwareVersion: String,
        val leftSerial: String?,
        val rightSerial: String?,
    ) : AapEvent

    data class StemPress(val type: PressType, val bud: Bud) : AapEvent

    data class ProximityKeys(val irk: ByteArray?, val encryptionKey: ByteArray?) : AapEvent {
        override fun equals(other: Any?): Boolean = other is ProximityKeys &&
            irk.contentEqualsNullable(other.irk) && encryptionKey.contentEqualsNullable(other.encryptionKey)

        override fun hashCode(): Int = 31 * (irk?.contentHashCode() ?: 0) + (encryptionKey?.contentHashCode() ?: 0)
    }

    /** Raw head-tracking sample. Units are undocumented; values are exposed as raw signed integers. */
    data class HeadTracking(
        val orientation1: Int,
        val orientation2: Int,
        val orientation3: Int,
        val horizontalAcceleration: Int,
        val verticalAcceleration: Int,
    ) : AapEvent

    data class Unknown(val opcode: Int, val payload: ByteArray) : AapEvent {
        override fun equals(other: Any?): Boolean =
            other is Unknown && other.opcode == opcode && other.payload.contentEquals(payload)

        override fun hashCode(): Int = 31 * opcode + payload.contentHashCode()
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}
