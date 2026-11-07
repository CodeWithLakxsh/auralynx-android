package app.auralynx.experimental.research

import app.auralynx.core.bluetooth.ble.ContinuitySighting
import app.auralynx.core.bluetooth.ble.ProximityScanner
import app.auralynx.core.common.u8
import app.auralynx.core.protocol.ble.Continuity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.runningFold
import javax.inject.Inject

/**
 * Passive observation of Apple Continuity advertisements, used for Apple Watch and ecosystem
 * research. It only listens; it never transmits, pairs, or connects.
 *
 * Interpretations of Nearby Info action codes follow Martin et al., "Handoff All Your Privacy"
 * (PETS 2019) and Celosia & Cunche, "Discontinued Privacy" (PETS 2020). They are labelled as
 * published interpretations, not facts verified by Auralynx.
 */
class ContinuityExplorer @Inject constructor(private val scanner: ProximityScanner) {

    data class Observation(
        val address: String,
        val lastRssi: Int,
        val lastSeenMillis: Long,
        val packets: Int,
        val messageTypes: Map<String, Int>,
        val nearbyInfo: NearbyInfo?,
    )

    data class NearbyInfo(val actionCode: Int, val statusFlags: Int, val dataFlags: Int?) {
        val actionDescription: String get() = ACTION_CODES[actionCode] ?: "Unknown action 0x%02X".format(actionCode)
        /** Published research associates action 0x0A with a wrist-worn, unlocked Apple Watch. Unverified here. */
        val possiblyWatchOnWrist: Boolean get() = actionCode == 0x0A
    }

    fun observe(): Flow<Map<String, Observation>> = scanner.continuity().runningFold(emptyMap()) { acc, s -> acc + (s.address to merge(acc[s.address], s)) }

    private fun merge(prev: Observation?, s: ContinuitySighting): Observation {
        val types = (prev?.messageTypes ?: emptyMap()).toMutableMap()
        s.messages.forEach { types[it.typeName] = (types[it.typeName] ?: 0) + 1 }
        val nearby = s.messages.firstOrNull { it.type == 0x10 }?.let { decodeNearbyInfo(it) } ?: prev?.nearbyInfo
        return Observation(s.address, s.rssi, s.timeMillis, (prev?.packets ?: 0) + 1, types, nearby)
    }

    companion object {
        fun decodeNearbyInfo(m: Continuity.Message): NearbyInfo? {
            if (m.value.isEmpty()) return null
            val b0 = m.value.u8(0)
            return NearbyInfo(actionCode = b0 and 0x0F, statusFlags = b0 shr 4, dataFlags = if (m.value.size > 1) m.value.u8(1) else null)
        }

        val ACTION_CODES: Map<Int, String> = mapOf(
            0x00 to "Activity level unknown",
            0x01 to "Activity reporting disabled",
            0x03 to "Idle user",
            0x05 to "Audio playing, screen locked",
            0x07 to "Active user (screen on)",
            0x09 to "Screen on with video",
            0x0A to "Watch on wrist and unlocked (published interpretation)",
            0x0B to "Recent user interaction",
            0x0D to "User is driving",
            0x0E to "Phone or FaceTime call",
        )
    }
}
