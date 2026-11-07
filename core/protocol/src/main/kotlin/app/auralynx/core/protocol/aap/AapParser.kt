package app.auralynx.core.protocol.aap

import app.auralynx.core.common.s16le
import app.auralynx.core.common.u16le
import app.auralynx.core.common.u8
import app.auralynx.core.protocol.model.BatteryComponent
import app.auralynx.core.protocol.model.BatteryReading
import app.auralynx.core.protocol.model.Bud
import app.auralynx.core.protocol.model.BudPlacement
import app.auralynx.core.protocol.model.ChargingState
import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.core.protocol.model.MeasurementSource
import app.auralynx.core.protocol.model.PressType

/**
 * Decodes packets received on the AAP channel.
 *
 * L2CAP preserves packet boundaries, so each socket read is one packet. The parser is defensive:
 * malformed or truncated packets never throw; they yield `null` (not an AAP data packet) or
 * [AapEvent.Unknown].
 */
object AapParser {

    private const val OPCODE_OFFSET = 4
    private const val PAYLOAD_OFFSET = 6

    fun parse(packet: ByteArray): AapEvent? {
        if (packet.size < PAYLOAD_OFFSET) return null
        for (i in AapConstants.HEADER.indices) if (packet[i] != AapConstants.HEADER[i]) return null

        val opcode = packet.u16le(OPCODE_OFFSET)
        val payload = packet.copyOfRange(PAYLOAD_OFFSET, packet.size)
        return runCatching {
            when (opcode) {
                AapOpcode.BATTERY -> parseBattery(payload)
                AapOpcode.EAR_DETECTION -> parseEarDetection(payload)
                AapOpcode.CONTROL -> parseControl(payload)
                AapOpcode.CONVERSATION_AWARENESS -> parseConversation(payload)
                AapOpcode.DEVICE_INFO -> parseDeviceInfo(payload)
                AapOpcode.STEM_PRESS -> parseStemPress(payload)
                AapOpcode.PROXIMITY_KEYS_RESPONSE -> parseProximityKeys(payload)
                AapOpcode.HEAD_TRACKING -> parseHeadTracking(packet)
                else -> null
            }
        }.getOrNull() ?: AapEvent.Unknown(opcode, payload)
    }

    /** `[count] ([component] 0x01 [level] [status] 0x01) * count` */
    private fun parseBattery(p: ByteArray): AapEvent.Battery? {
        if (p.isEmpty()) return null
        val count = p.u8(0)
        val readings = buildList {
            var offset = 1
            repeat(count) {
                if (offset + 5 > p.size) return@repeat
                val component = when (p.u8(offset)) {
                    0x01 -> BatteryComponent.SINGLE
                    0x02 -> BatteryComponent.RIGHT
                    0x04 -> BatteryComponent.LEFT
                    0x08 -> BatteryComponent.CASE
                    else -> null
                }
                val level = p.u8(offset + 2)
                val charging = when (p.u8(offset + 3)) {
                    0x01, 0x05 -> ChargingState.CHARGING
                    0x02 -> ChargingState.DISCHARGING
                    0x04 -> ChargingState.DISCONNECTED
                    else -> ChargingState.UNKNOWN
                }
                offset += 5
                // Levels above 100 are placeholders (0x7F after case close, 0xFF when disconnected).
                if (component != null && level <= 100 && charging != ChargingState.DISCONNECTED) {
                    add(BatteryReading(component, level, charging, MeasurementSource.ACCESSORY_CHANNEL))
                }
            }
        }
        return AapEvent.Battery(readings)
    }

    private fun parseEarDetection(p: ByteArray): AapEvent.EarDetection? {
        if (p.size < 2) return null
        return AapEvent.EarDetection(BudPlacement.fromWire(p.u8(0)), BudPlacement.fromWire(p.u8(1)))
    }

    private fun parseControl(p: ByteArray): AapEvent? {
        if (p.size < 2) return null
        val id = p.u8(0)
        val v = p.u8(1)
        return when (id) {
            AapControl.LISTENING_MODE -> ListeningMode.fromWire(v)?.let { AapEvent.ListeningModeChanged(it) }
            AapControl.LISTENING_MODE_CYCLE -> AapEvent.ListeningModeCycleChanged(ListeningMode.fromCycleMask(v))
            AapControl.CONVERSATION_AWARENESS -> when (v) {
                0x01 -> AapEvent.ConversationAwarenessSetting(true)
                0x02 -> AapEvent.ConversationAwarenessSetting(false)
                else -> null
            }
            AapControl.ADAPTIVE_NOISE_LEVEL -> if (v in 0..100) AapEvent.AdaptiveNoiseLevel(v) else null
            else -> null
        } ?: AapEvent.ControlValue(id, p.copyOfRange(1, minOf(p.size, 5)))
    }

    /** `02 00 01 [level]` */
    private fun parseConversation(p: ByteArray): AapEvent.ConversationActivity? {
        if (p.size < 4) return null
        return AapEvent.ConversationActivity(p.u8(3))
    }

    /**
     * Device information: a few binary bytes followed by NUL-terminated UTF-8 strings in the
     * order name, model number, manufacturer, serial, firmware, firmware (again), software
     * version, updater id, left serial, right serial, ...
     */
    private fun parseDeviceInfo(p: ByteArray): AapEvent.DeviceInfo? {
        val start = p.indexOfFirst { it in 0x20..0x7E }
        if (start < 0) return null
        val strings = splitNulTerminated(p, start)
        if (strings.size < 4) return null
        return AapEvent.DeviceInfo(
            name = strings[0],
            modelNumber = strings[1],
            manufacturer = strings[2],
            serialNumber = strings[3],
            firmwareVersion = strings.getOrElse(4) { "" },
            leftSerial = strings.getOrNull(8)?.takeIf { it.isPlausibleSerial() },
            rightSerial = strings.getOrNull(9)?.takeIf { it.isPlausibleSerial() },
        )
    }

    private fun parseStemPress(p: ByteArray): AapEvent.StemPress? {
        if (p.size < 2) return null
        val type = PressType.fromWire(p.u8(0)) ?: return null
        val bud = when (p.u8(1)) {
            0x01 -> Bud.LEFT
            0x02 -> Bud.RIGHT
            else -> return null
        }
        return AapEvent.StemPress(type, bud)
    }

    /** `[count] ([type] [?] [length] [?] [key bytes]) * count` */
    private fun parseProximityKeys(p: ByteArray): AapEvent.ProximityKeys? {
        if (p.isEmpty()) return null
        var irk: ByteArray? = null
        var enc: ByteArray? = null
        var offset = 1
        repeat(p.u8(0)) {
            if (offset + 4 > p.size) return@repeat
            val type = p.u8(offset)
            val length = p.u8(offset + 2)
            offset += 4
            if (offset + length > p.size) return@repeat
            val key = p.copyOfRange(offset, offset + length)
            offset += length
            if (length == 16) when (type) {
                AapKeyType.IRK -> irk = key
                AapKeyType.ENCRYPTION_KEY -> enc = key
            }
        }
        return if (irk != null || enc != null) AapEvent.ProximityKeys(irk, enc) else null
    }

    private fun parseHeadTracking(packet: ByteArray): AapEvent.HeadTracking? {
        if (packet.size < 55) return null
        return AapEvent.HeadTracking(
            orientation1 = packet.s16le(43),
            orientation2 = packet.s16le(45),
            orientation3 = packet.s16le(47),
            horizontalAcceleration = packet.s16le(51),
            verticalAcceleration = packet.s16le(53),
        )
    }

    private fun splitNulTerminated(p: ByteArray, start: Int): List<String> {
        val out = mutableListOf<String>()
        var i = start
        while (i < p.size) {
            var end = i
            while (end < p.size && p[end] != 0.toByte()) end++
            out += p.copyOfRange(i, end).decodeToString()
            i = end + 1
        }
        return out
    }

    private fun String.isPlausibleSerial(): Boolean = length in 8..16 && all { it.isLetterOrDigit() }
}
