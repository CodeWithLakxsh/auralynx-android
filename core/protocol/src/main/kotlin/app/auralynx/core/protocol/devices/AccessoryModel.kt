package app.auralynx.core.protocol.devices

/** Physical form factor; drives UI layout (two buds + case vs. a single headset). */
enum class FormFactor { EARBUDS_WITH_CASE, HEADPHONES, NECKBAND_OR_SINGLE }

/**
 * Known accessory models.
 *
 * - [bleModelCode]: model code in Proximity Pairing advertisements (null when not yet documented).
 * - [modelNumbers]: Apple model numbers reported by AAP device information.
 * - [declaredFeatures]: features community implementations report as working for this model over
 *   AAP. They are *declared*, not verified by Auralynx; see [FeatureEvidence].
 * - [listeningModes]: listening modes the hardware offers.
 *
 * Sources: LibrePods model table, CAPod PodModel table, Apple support documentation for feature
 * availability per model.
 */
enum class AccessoryModel(
    val displayName: String,
    val bleModelCode: Int?,
    val modelNumbers: Set<String>,
    val formFactor: FormFactor,
    val declaredFeatures: Set<Feature>,
    val listeningModes: Set<app.auralynx.core.protocol.model.ListeningMode> = emptySet(),
    /** True when no community implementation has confirmed AAP control for this model. */
    val aapControlUnverified: Boolean = false,
) {
    AIRPODS_1("AirPods (1st generation)", 0x0220, setOf("A1523", "A1722"), FormFactor.EARBUDS_WITH_CASE, BASE_BUDS),
    AIRPODS_2("AirPods (2nd generation)", 0x0F20, setOf("A2031", "A2032"), FormFactor.EARBUDS_WITH_CASE, BASE_BUDS),
    AIRPODS_3("AirPods (3rd generation)", 0x1320, setOf("A2564", "A2565"), FormFactor.EARBUDS_WITH_CASE,
        BASE_BUDS + Feature.HEAD_GESTURES + Feature.HEAD_TRACKING),
    AIRPODS_4("AirPods 4", 0x1920, setOf("A3050", "A3053", "A3054"), FormFactor.EARBUDS_WITH_CASE,
        BASE_BUDS + Feature.HEAD_GESTURES + Feature.HEAD_TRACKING),
    AIRPODS_4_ANC("AirPods 4 with Active Noise Cancellation", 0x1B20, setOf("A3055", "A3056", "A3057"),
        FormFactor.EARBUDS_WITH_CASE, ANC_BUDS + Feature.ADAPTIVE_AUDIO, ALL_MODES),
    AIRPODS_PRO("AirPods Pro (1st generation)", 0x0E20, setOf("A2083", "A2084"), FormFactor.EARBUDS_WITH_CASE,
        BASE_BUDS + Feature.NOISE_CONTROL + Feature.HEAD_TRACKING, CLASSIC_MODES),
    AIRPODS_PRO_2("AirPods Pro 2 (Lightning)", 0x1420, setOf("A2698", "A2699", "A2931"), FormFactor.EARBUDS_WITH_CASE,
        ANC_BUDS + Feature.ADAPTIVE_AUDIO + Feature.LOUD_SOUND_REDUCTION + Feature.HEARING_ASSIST, ALL_MODES),
    AIRPODS_PRO_2_USBC("AirPods Pro 2 (USB-C)", 0x2420, setOf("A3047", "A3048", "A3049"), FormFactor.EARBUDS_WITH_CASE,
        ANC_BUDS + Feature.ADAPTIVE_AUDIO + Feature.LOUD_SOUND_REDUCTION + Feature.HEARING_ASSIST, ALL_MODES),
    AIRPODS_PRO_3("AirPods Pro 3", 0x2720, setOf("A3063", "A3064", "A3065"), FormFactor.EARBUDS_WITH_CASE,
        ANC_BUDS + Feature.ADAPTIVE_AUDIO + Feature.LOUD_SOUND_REDUCTION + Feature.HEARING_ASSIST + Feature.HEART_RATE,
        ALL_MODES),
    AIRPODS_MAX("AirPods Max (Lightning)", 0x0A20, setOf("A2096"), FormFactor.HEADPHONES,
        setOf(Feature.BATTERY, Feature.NOISE_CONTROL, Feature.HEAD_TRACKING, Feature.RENAME), CLASSIC_MODES,
        aapControlUnverified = true),
    AIRPODS_MAX_USBC("AirPods Max (USB-C)", 0x1F20, setOf("A3184"), FormFactor.HEADPHONES,
        setOf(Feature.BATTERY, Feature.NOISE_CONTROL, Feature.HEAD_TRACKING, Feature.RENAME), CLASSIC_MODES,
        aapControlUnverified = true),
    AIRPODS_MAX_2("AirPods Max 2", null, setOf("A3454"), FormFactor.HEADPHONES,
        setOf(Feature.BATTERY, Feature.NOISE_CONTROL, Feature.HEAD_TRACKING, Feature.RENAME), CLASSIC_MODES,
        aapControlUnverified = true),

    POWERBEATS_3("Powerbeats3", 0x0320, setOf("A1747"), FormFactor.NECKBAND_OR_SINGLE, BLE_ONLY),
    BEATS_X("BeatsX", 0x0520, setOf("A1763"), FormFactor.NECKBAND_OR_SINGLE, BLE_ONLY),
    BEATS_SOLO_3("Beats Solo3", 0x0620, setOf("A1796"), FormFactor.HEADPHONES, BLE_ONLY),
    BEATS_STUDIO_3("Beats Studio3", 0x0920, setOf("A1914"), FormFactor.HEADPHONES, BLE_ONLY),
    POWERBEATS_PRO("Powerbeats Pro", 0x0B20, setOf("A2047", "A2048", "A2453", "A2454"), FormFactor.EARBUDS_WITH_CASE, BLE_BUDS),
    BEATS_SOLO_PRO("Beats Solo Pro", 0x0C20, setOf("A1881"), FormFactor.HEADPHONES, BLE_ONLY),
    POWERBEATS_4("Powerbeats", 0x0D20, setOf("A2015"), FormFactor.NECKBAND_OR_SINGLE, BLE_ONLY),
    BEATS_FLEX("Beats Flex", 0x1020, setOf("A2295"), FormFactor.NECKBAND_OR_SINGLE, BLE_ONLY),
    BEATS_STUDIO_BUDS("Beats Studio Buds", 0x1120, setOf("A2512", "A2513", "A2514"), FormFactor.EARBUDS_WITH_CASE, BLE_BUDS),
    BEATS_FIT_PRO("Beats Fit Pro", 0x1220, setOf("A2576", "A2577", "A2578"), FormFactor.EARBUDS_WITH_CASE, BLE_BUDS),
    BEATS_STUDIO_BUDS_PLUS("Beats Studio Buds +", 0x1620, setOf("A2871", "A2872", "A2952"), FormFactor.EARBUDS_WITH_CASE, BLE_BUDS),
    BEATS_STUDIO_PRO("Beats Studio Pro", 0x1720, setOf("A2924"), FormFactor.HEADPHONES, BLE_ONLY),
    POWERBEATS_PRO_2("Powerbeats Pro 2", 0x1D20, setOf("A3157", "A3158", "A3159"), FormFactor.EARBUDS_WITH_CASE, BLE_BUDS),
    BEATS_SOLO_4("Beats Solo 4", 0x2520, setOf("A3140"), FormFactor.HEADPHONES, BLE_ONLY),
    BEATS_SOLO_BUDS("Beats Solo Buds", 0x2620, setOf("A3150", "A3151", "A3153"), FormFactor.EARBUDS_WITH_CASE, BLE_BUDS),

    /** An Apple/Beats accessory whose model is not (yet) known. Only standard features are offered. */
    UNKNOWN("Unknown Apple accessory", null, emptySet(), FormFactor.EARBUDS_WITH_CASE, setOf(Feature.BATTERY));

    val isAirPodsMax: Boolean get() = name.startsWith("AIRPODS_MAX")

    companion object {
        fun fromBleModelCode(code: Int): AccessoryModel? = entries.firstOrNull { it.bleModelCode == code }

        fun fromModelNumber(number: String): AccessoryModel? {
            val n = number.trim().uppercase()
            return entries.firstOrNull { n in it.modelNumbers }
        }

        /** Best-effort match on the Bluetooth name. Used only as a hint before stronger evidence. */
        fun guessFromName(name: String?): AccessoryModel? {
            val n = name?.lowercase() ?: return null
            return when {
                "airpods max" in n -> AIRPODS_MAX
                "airpods pro" in n -> AIRPODS_PRO_2
                "airpods" in n -> AIRPODS_2
                "beats" in n || "powerbeats" in n -> UNKNOWN
                else -> null
            }
        }
    }
}

private val BASE_BUDS = setOf(Feature.BATTERY, Feature.EAR_DETECTION, Feature.RENAME)
private val ANC_BUDS = BASE_BUDS + setOf(
    Feature.NOISE_CONTROL, Feature.CONVERSATION_AWARENESS, Feature.STEM_PRESS_EVENTS,
    Feature.HEAD_GESTURES, Feature.HEAD_TRACKING,
)
private val BLE_ONLY = setOf(Feature.BATTERY)
private val BLE_BUDS = setOf(Feature.BATTERY, Feature.EAR_DETECTION)
private val CLASSIC_MODES = setOf(
    app.auralynx.core.protocol.model.ListeningMode.OFF,
    app.auralynx.core.protocol.model.ListeningMode.NOISE_CANCELLATION,
    app.auralynx.core.protocol.model.ListeningMode.TRANSPARENCY,
)
private val ALL_MODES = CLASSIC_MODES + app.auralynx.core.protocol.model.ListeningMode.ADAPTIVE
