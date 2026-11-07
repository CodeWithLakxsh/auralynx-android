package app.auralynx.core.protocol.model

/** Listening (noise-control) modes, using the wire values of AAP control identifier 0x0D. */
enum class ListeningMode(val wire: Int) {
    OFF(0x01),
    NOISE_CANCELLATION(0x02),
    TRANSPARENCY(0x03),
    ADAPTIVE(0x04);

    /** Bit used in the listening-mode cycle mask (AAP control 0x1A). */
    val cycleBit: Int
        get() = when (this) {
            OFF -> 0x01
            NOISE_CANCELLATION -> 0x02
            TRANSPARENCY -> 0x04
            ADAPTIVE -> 0x08
        }

    companion object {
        fun fromWire(value: Int): ListeningMode? = entries.firstOrNull { it.wire == value }

        fun fromCycleMask(mask: Int): Set<ListeningMode> = entries.filter { mask and it.cycleBit != 0 }.toSet()

        fun toCycleMask(modes: Set<ListeningMode>): Int = modes.fold(0) { acc, m -> acc or m.cycleBit }
    }
}

enum class BatteryComponent { LEFT, RIGHT, CASE, SINGLE }

/** Where a measurement came from. Precision differs per source and is always shown to the user. */
enum class MeasurementSource(val precisionPercent: Int) {
    /** Accessory channel (AAP) battery report: 1 % resolution. */
    ACCESSORY_CHANNEL(1),

    /** Decrypted proximity advertisement (needs keys obtained over the accessory channel): 1 %. */
    BLE_ENCRYPTED(1),

    /** Public proximity advertisement: 10 % resolution. */
    BLE_ADVERTISEMENT(10),

    /** Standard Bluetooth HFP battery indicator reported by Android: typically 10 % steps. */
    STANDARD_BLUETOOTH(10),
}

enum class ChargingState { CHARGING, DISCHARGING, DISCONNECTED, UNKNOWN }

data class BatteryReading(
    val component: BatteryComponent,
    /** 0..100, exactly as received (never interpolated). */
    val level: Int,
    val charging: ChargingState,
    val source: MeasurementSource,
)

/** Per-bud placement as reported by AAP ear-detection (opcode 0x06). */
enum class BudPlacement(val wire: Int) {
    IN_EAR(0x00),
    OUT_OF_EAR(0x01),
    IN_CASE(0x02),
    UNKNOWN(-1);

    companion object {
        fun fromWire(value: Int): BudPlacement = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class Bud { LEFT, RIGHT }

enum class PressType(val wire: Int) {
    SINGLE(0x05), DOUBLE(0x06), TRIPLE(0x07), LONG(0x08);

    /** Bit in the stem-press claim mask (AAP control 0x39). */
    val claimBit: Int
        get() = when (this) {
            SINGLE -> 0x01
            DOUBLE -> 0x02
            TRIPLE -> 0x04
            LONG -> 0x08
        }

    companion object {
        fun fromWire(value: Int): PressType? = entries.firstOrNull { it.wire == value }
    }
}
