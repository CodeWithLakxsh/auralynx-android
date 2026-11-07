package app.auralynx.core.protocol.aap

import app.auralynx.core.protocol.model.ListeningMode
import app.auralynx.core.protocol.model.PressType

/**
 * Encoders for packets Auralynx sends to the accessory.
 *
 * Every function returns a complete, ready-to-write L2CAP payload. They are pure functions so that
 * byte layouts are covered by unit tests (see AapCommandsTest).
 */
object AapCommands {

    fun handshake(): ByteArray = AapConstants.HANDSHAKE.copyOf()

    /**
     * Announces host capabilities. The 0xD7 flag set is what current community implementations send
     * to unlock Conversation Awareness during playback and Adaptive listening mode.
     */
    fun hostFeatures(): ByteArray = packet(
        AapOpcode.HOST_FEATURES,
        byteArrayOf(0xD7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
    )

    /** Subscribes to battery, ear-detection, listening-mode and other notifications. */
    fun requestNotifications(): ByteArray = packet(
        AapOpcode.REQUEST_NOTIFICATIONS,
        byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
    )

    /** Generic control write: header, opcode 0x0009, identifier, four value bytes. */
    fun control(identifier: Int, value: Int): ByteArray = control(identifier, byteArrayOf(value.toByte(), 0, 0, 0))

    fun control(identifier: Int, value: ByteArray): ByteArray {
        require(value.size == 4) { "Control values are four bytes" }
        return packet(AapOpcode.CONTROL, byteArrayOf(identifier.toByte()) + value)
    }

    fun setListeningMode(mode: ListeningMode): ByteArray = control(AapControl.LISTENING_MODE, mode.wire)

    /** Which modes a long press cycles through. At least two modes are required by the firmware. */
    fun setListeningModeCycle(modes: Set<ListeningMode>): ByteArray {
        require(modes.size >= 2) { "A long-press cycle needs at least two modes" }
        return control(AapControl.LISTENING_MODE_CYCLE, ListeningMode.toCycleMask(modes))
    }

    fun setConversationAwareness(enabled: Boolean): ByteArray =
        control(AapControl.CONVERSATION_AWARENESS, if (enabled) 0x01 else 0x02)

    /** Adaptive-mode noise filtering strength, 0 (more ambient sound) to 100 (less ambient sound). */
    fun setAdaptiveNoiseLevel(level: Int): ByteArray =
        control(AapControl.ADAPTIVE_NOISE_LEVEL, level.coerceIn(0, 100))

    fun setOneBudAnc(enabled: Boolean): ByteArray = control(AapControl.ONE_BUD_ANC, if (enabled) 0x01 else 0x02)

    fun setAllowOffMode(enabled: Boolean): ByteArray = control(AapControl.ALLOW_OFF_MODE, if (enabled) 0x01 else 0x02)

    fun setEarDetectionEnabled(enabled: Boolean): ByteArray =
        control(AapControl.EAR_DETECTION_ENABLED, if (enabled) 0x01 else 0x02)

    /**
     * Claims stem presses so the accessory reports them to the host (opcode 0x19) instead of
     * handling them itself. Only claimed press types are forwarded.
     */
    fun claimStemPresses(types: Set<PressType>): ByteArray =
        control(AapControl.STEM_PRESS_CLAIM, types.fold(0) { acc, t -> acc or t.claimBit } and 0x0F)

    /** Renames the accessory. Android may keep showing the old name until the device is re-paired. */
    fun rename(name: String): ByteArray {
        val bytes = name.trim().encodeToByteArray()
        require(bytes.isNotEmpty()) { "Name must not be empty" }
        require(bytes.size <= MAX_NAME_BYTES) { "Name must be at most $MAX_NAME_BYTES UTF-8 bytes" }
        return packet(AapOpcode.RENAME, byteArrayOf(0x01, bytes.size.toByte(), 0x00) + bytes)
    }

    /** Requests the identity-resolving key and the advertisement encryption key. */
    fun requestProximityKeys(): ByteArray = packet(
        AapOpcode.PROXIMITY_KEYS_REQUEST,
        byteArrayOf((AapKeyType.IRK or AapKeyType.ENCRYPTION_KEY).toByte(), 0x00),
    )

    fun startHeadTracking(): ByteArray = packet(
        AapOpcode.HEAD_TRACKING,
        byteArrayOf(
            0x00, 0x00, 0x10, 0x00, 0x10, 0x00, 0x08, 0xA1.toByte(), 0x02, 0x42, 0x0B, 0x08, 0x0E,
            0x10, 0x02, 0x1A, 0x05, 0x01, 0x40, 0x9C.toByte(), 0x00, 0x00,
        ),
    )

    fun stopHeadTracking(): ByteArray = packet(
        AapOpcode.HEAD_TRACKING,
        byteArrayOf(
            0x00, 0x00, 0x10, 0x00, 0x11, 0x00, 0x08, 0x7E, 0x10, 0x02, 0x42, 0x0B, 0x08, 0x4E,
            0x10, 0x02, 0x1A, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00,
        ),
    )

    fun packet(opcode: Int, payload: ByteArray): ByteArray =
        AapConstants.HEADER + byteArrayOf((opcode and 0xFF).toByte(), ((opcode shr 8) and 0xFF).toByte()) + payload

    const val MAX_NAME_BYTES: Int = 32
}
