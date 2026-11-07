package app.auralynx.core.protocol.aap

/**
 * Apple Accessory Protocol (AAP, also called AACP) constants.
 *
 * AAP runs over a classic Bluetooth L2CAP channel on PSM 0x1001. Values in this file come from
 * public reverse-engineering documentation (see THIRD_PARTY_NOTICES.md: LibrePods "AAP
 * Definitions", CAPod, and the apple-wireshark dissector). Each value was cross-checked between at
 * least two independent sources. None of them have been validated by the Auralynx project on
 * physical hardware yet; see COMPATIBILITY.md.
 */
object AapConstants {
    const val PSM: Int = 0x1001

    /** Data packets start with this 4-byte header, followed by a 16-bit little-endian opcode. */
    val HEADER: ByteArray = byteArrayOf(0x04, 0x00, 0x04, 0x00)

    /** Session-open packet. Without it the accessory does not answer any other packet. */
    val HANDSHAKE: ByteArray = byteArrayOf(
        0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )
}

/** 16-bit AAP opcodes (sent little-endian after the header). */
object AapOpcode {
    const val BATTERY: Int = 0x0004
    const val EAR_DETECTION: Int = 0x0006
    const val CONTROL: Int = 0x0009
    const val REQUEST_NOTIFICATIONS: Int = 0x000F
    const val HEAD_TRACKING: Int = 0x0017
    const val STEM_PRESS: Int = 0x0019
    const val RENAME: Int = 0x001A
    const val DEVICE_INFO: Int = 0x001D
    const val PROXIMITY_KEYS_REQUEST: Int = 0x0030
    const val PROXIMITY_KEYS_RESPONSE: Int = 0x0031
    const val CONVERSATION_AWARENESS: Int = 0x004B
    const val HOST_FEATURES: Int = 0x004D
}

/** Identifiers carried inside CONTROL (0x0009) packets. */
object AapControl {
    const val LISTENING_MODE: Int = 0x0D
    const val LISTENING_MODE_CYCLE: Int = 0x1A
    const val ONE_BUD_ANC: Int = 0x1B
    const val CONVERSATION_AWARENESS: Int = 0x28
    const val ADAPTIVE_NOISE_LEVEL: Int = 0x2E
    const val ALLOW_OFF_MODE: Int = 0x34
    const val SLEEP_DETECTION: Int = 0x35
    const val STEM_PRESS_CLAIM: Int = 0x39
    const val EAR_DETECTION_ENABLED: Int = 0x0A
}

/** Key types used by the proximity-key exchange (opcode 0x30 / 0x31). */
object AapKeyType {
    const val IRK: Int = 0x01
    const val ENCRYPTION_KEY: Int = 0x04
}
