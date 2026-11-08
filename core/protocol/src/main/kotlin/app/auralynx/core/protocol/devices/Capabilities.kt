package app.auralynx.core.protocol.devices

/** User-facing accessory features. */
enum class Feature {
    BATTERY,
    EAR_DETECTION,
    NOISE_CONTROL,
    ADAPTIVE_AUDIO,
    CONVERSATION_AWARENESS,
    STEM_PRESS_EVENTS,
    HEAD_GESTURES,
    HEAD_TRACKING,
    RENAME,
    SPATIAL_AUDIO,
    ADVANCED_MICROPHONE,
    LOUD_SOUND_REDUCTION,
    HEARING_ASSIST,
    HEART_RATE,
}

/** The transport a feature depends on. */
enum class Transport {
    /** Standard Bluetooth profiles exposed by public Android APIs (A2DP/HFP connection, HFP battery). */
    STANDARD_BLUETOOTH,

    /** Public BLE scanning of proximity advertisements. Works without root on all devices. */
    BLE_ADVERTISEMENT,

    /** Proprietary AAP channel over classic L2CAP. Not exposed by any public Android API as of API 37. */
    ACCESSORY_CHANNEL,
}

/** How strongly Auralynx believes a feature works. */
enum class FeatureEvidence {
    /** Seen working on this accessory during this or a previous session (we received matching data). */
    OBSERVED,

    /** Reported working for this model by community implementations; not yet seen on this accessory. */
    DECLARED,

    /** Plausible for this model but no implementation has confirmed it. */
    UNVERIFIED,
}

/** Support status of one feature for one accessory, given current Android/transport conditions. */
sealed interface FeatureSupport {
    data class Available(val transport: Transport, val evidence: FeatureEvidence) : FeatureSupport

    /** The model supports it, but the AAP channel is not open (not supported by this OS, or not connected). */
    data class NeedsAccessoryChannel(val reason: ChannelUnavailableReason) : FeatureSupport

    /** Needs Apple's Device ID in the phone's Bluetooth stack (root/Xposed). Not offered by Auralynx. */
    data object RequiresPrivilegedAccess : FeatureSupport

    /** Research-only. Disabled unless the user enables experimental features. */
    data object Experimental : FeatureSupport

    data object NotSupportedByModel : FeatureSupport
}

enum class ChannelUnavailableReason {
    /** The OS does not expose classic L2CAP sockets to apps (Android 16 and older). */
    OS_UNSUPPORTED,

    /** Supported by the OS but not connected yet (or connection failed). */
    NOT_CONNECTED,

    /** The user disabled the accessory channel in settings. */
    DISABLED_BY_USER,
}

/** Current transport conditions used to resolve capabilities. */
data class TransportState(
    val standardConnected: Boolean,
    val bleObserved: Boolean,
    val channelConnected: Boolean,
    val channelUnavailableReason: ChannelUnavailableReason?,
    val experimentalEnabled: Boolean,
    /** Android reported an HFP battery value for this device. */
    val standardBatteryReported: Boolean = false,
)

/**
 * Capability profile of a specific accessory, as consumed by the UI.
 *
 * The boolean properties mirror the specification's `AccessoryCapabilities` contract: each is true
 * only when the feature is *currently operable* (an implemented command path exists and its
 * transport is up). [support] explains every other case so the UI can show the limitation.
 */
data class AccessoryCapabilities(
    val model: AccessoryModel,
    val support: Map<Feature, FeatureSupport>,
) {
    fun isOperable(feature: Feature): Boolean = support[feature] is FeatureSupport.Available

    val batteryMonitoring: Boolean get() = isOperable(Feature.BATTERY)
    val noiseControl: Boolean get() = isOperable(Feature.NOISE_CONTROL)
    val earDetection: Boolean get() = isOperable(Feature.EAR_DETECTION)
    val adaptiveAudio: Boolean get() = isOperable(Feature.ADAPTIVE_AUDIO)
    val conversationAwareness: Boolean get() = isOperable(Feature.CONVERSATION_AWARENESS)
    val headGestures: Boolean get() = isOperable(Feature.HEAD_GESTURES)
    val spatialAudio: Boolean get() = isOperable(Feature.SPATIAL_AUDIO)
    val advancedMicrophone: Boolean get() = isOperable(Feature.ADVANCED_MICROPHONE)
}

/** Resolves an [AccessoryCapabilities] profile. Pure function; exhaustively unit tested. */
object CapabilityRegistry {

    /** Features that only ever work through the AAP channel. */
    private val CHANNEL_FEATURES = setOf(
        Feature.NOISE_CONTROL, Feature.ADAPTIVE_AUDIO, Feature.CONVERSATION_AWARENESS,
        Feature.STEM_PRESS_EVENTS, Feature.RENAME,
    )

    /** Features that need the Apple VendorID in the phone's Device ID record (root only). */
    private val PRIVILEGED_FEATURES = setOf(Feature.LOUD_SOUND_REDUCTION, Feature.HEARING_ASSIST)

    /** Features Auralynx only offers as research. */
    private val EXPERIMENTAL_FEATURES = setOf(Feature.HEAD_TRACKING, Feature.HEAD_GESTURES, Feature.HEART_RATE)

    fun resolve(
        model: AccessoryModel,
        transport: TransportState,
        observed: Set<Feature> = emptySet(),
    ): AccessoryCapabilities {
        val map = Feature.entries.associateWith { feature -> resolveOne(model, feature, transport, observed) }
        return AccessoryCapabilities(model, map)
    }

    private fun resolveOne(
        model: AccessoryModel,
        feature: Feature,
        t: TransportState,
        observed: Set<Feature>,
    ): FeatureSupport {
        // Spatial audio and advanced microphone are not accessory features Auralynx can drive.
        // App-local spatial processing is offered separately by the audio module.
        if (feature == Feature.SPATIAL_AUDIO || feature == Feature.ADVANCED_MICROPHONE) {
            return FeatureSupport.Experimental
        }
        if (feature !in model.declaredFeatures && feature !in observed) return FeatureSupport.NotSupportedByModel
        if (feature in PRIVILEGED_FEATURES) return FeatureSupport.RequiresPrivilegedAccess

        val evidence = when {
            feature in observed -> FeatureEvidence.OBSERVED
            model.aapControlUnverified && feature in CHANNEL_FEATURES -> FeatureEvidence.UNVERIFIED
            else -> FeatureEvidence.DECLARED
        }

        if (feature in EXPERIMENTAL_FEATURES) {
            return if (t.experimentalEnabled && t.channelConnected) {
                FeatureSupport.Available(Transport.ACCESSORY_CHANNEL, evidence)
            } else if (!t.experimentalEnabled) {
                FeatureSupport.Experimental
            } else {
                FeatureSupport.NeedsAccessoryChannel(t.channelUnavailableReason ?: ChannelUnavailableReason.NOT_CONNECTED)
            }
        }

        return when (feature) {
            Feature.BATTERY, Feature.EAR_DETECTION -> when {
                t.channelConnected -> FeatureSupport.Available(Transport.ACCESSORY_CHANNEL, evidence)
                t.bleObserved -> FeatureSupport.Available(Transport.BLE_ADVERTISEMENT, evidence)
                t.standardConnected && t.standardBatteryReported && feature == Feature.BATTERY ->
                    FeatureSupport.Available(Transport.STANDARD_BLUETOOTH, evidence)
                else -> FeatureSupport.NeedsAccessoryChannel(
                    t.channelUnavailableReason ?: ChannelUnavailableReason.NOT_CONNECTED,
                )
            }
            else -> if (t.channelConnected) {
                FeatureSupport.Available(Transport.ACCESSORY_CHANNEL, evidence)
            } else {
                FeatureSupport.NeedsAccessoryChannel(t.channelUnavailableReason ?: ChannelUnavailableReason.NOT_CONNECTED)
            }
        }
    }
}
