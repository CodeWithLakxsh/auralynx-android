package app.auralynx.ui.common

import app.auralynx.core.bluetooth.BleMatch
import app.auralynx.core.bluetooth.ChannelStatus
import app.auralynx.core.bluetooth.ModelEvidence
import app.auralynx.core.designsystem.StatusKind
import app.auralynx.core.protocol.devices.ChannelUnavailableReason
import app.auralynx.core.protocol.devices.Feature
import app.auralynx.core.protocol.devices.FeatureEvidence
import app.auralynx.core.protocol.devices.FeatureSupport
import app.auralynx.core.protocol.devices.Transport
import app.auralynx.core.protocol.model.BatteryReading
import app.auralynx.core.protocol.model.MeasurementSource
import java.text.DateFormat
import java.util.Date

data class SupportLabel(val status: String, val kind: StatusKind, val explanation: String?)

fun Feature.title(): String = when (this) {
    Feature.BATTERY -> "Battery"
    Feature.EAR_DETECTION -> "Ear detection"
    Feature.NOISE_CONTROL -> "Noise control"
    Feature.ADAPTIVE_AUDIO -> "Adaptive mode"
    Feature.CONVERSATION_AWARENESS -> "Conversation awareness"
    Feature.STEM_PRESS_EVENTS -> "Press events"
    Feature.HEAD_GESTURES -> "Head gestures"
    Feature.HEAD_TRACKING -> "Head tracking"
    Feature.RENAME -> "Rename"
    Feature.SPATIAL_AUDIO -> "Accessory spatial audio"
    Feature.ADVANCED_MICROPHONE -> "High-quality microphone"
    Feature.LOUD_SOUND_REDUCTION -> "Loud sound reduction"
    Feature.HEARING_ASSIST -> "Hearing assistance"
    Feature.HEART_RATE -> "Heart rate"
}

fun FeatureSupport.label(): SupportLabel = when (this) {
    is FeatureSupport.Available -> SupportLabel(
        "Available", StatusKind.LIVE,
        buildString {
            append(when (transport) {
                Transport.ACCESSORY_CHANNEL -> "Through the accessory channel"
                Transport.BLE_ADVERTISEMENT -> "From Bluetooth advertisements (rootless, 10 % steps)"
                Transport.STANDARD_BLUETOOTH -> "From standard Bluetooth"
            })
            append(when (evidence) {
                FeatureEvidence.OBSERVED -> " · seen working on this accessory"
                FeatureEvidence.DECLARED -> " · reported for this model"
                FeatureEvidence.UNVERIFIED -> " · unverified on this model"
            })
        },
    )
    is FeatureSupport.NeedsAccessoryChannel -> when (reason) {
        ChannelUnavailableReason.OS_UNSUPPORTED -> SupportLabel("Not available on Android", StatusKind.UNAVAILABLE,
            "Uses Apple's accessory protocol over a classic Bluetooth L2CAP channel. Android does not offer apps a public API for that channel yet; Auralynx re-checks automatically after OS updates.")
        ChannelUnavailableReason.NOT_CONNECTED -> SupportLabel("Not connected", StatusKind.LIMITED, "Waiting for the accessory channel.")
        ChannelUnavailableReason.DISABLED_BY_USER -> SupportLabel("Turned off", StatusKind.UNAVAILABLE, "The accessory channel is disabled in Settings.")
    }
    FeatureSupport.RequiresPrivilegedAccess -> SupportLabel("Needs root", StatusKind.UNAVAILABLE,
        "The accessory only enables this for phones that identify as Apple devices. Auralynx does not modify the system Bluetooth stack.")
    FeatureSupport.Experimental -> SupportLabel("Experimental", StatusKind.EXPERIMENTAL, "Research only. Enable experimental features to try it.")
    FeatureSupport.NotSupportedByModel -> SupportLabel("Not on this model", StatusKind.UNAVAILABLE, null)
}

fun ChannelStatus.label(): SupportLabel = when (this) {
    ChannelStatus.Connected -> SupportLabel("Accessory channel connected", StatusKind.LIVE, null)
    is ChannelStatus.Connecting -> SupportLabel("Connecting…", StatusKind.LIMITED, "Attempt $attempt")
    ChannelStatus.DisabledByUser -> SupportLabel("Accessory channel off", StatusKind.UNAVAILABLE, "Enable it in Settings to use controls.")
    is ChannelStatus.OsUnsupported -> SupportLabel("Rootless mode", StatusKind.LIMITED, "Battery and wear status come from Bluetooth advertisements. " + detail)
    is ChannelStatus.Failed -> SupportLabel(if (nextRetryAtMillis != null) "Retrying" else "Channel unavailable", StatusKind.LIMITED, reason)
    ChannelStatus.Idle -> SupportLabel("Standard Bluetooth", StatusKind.LIMITED, null)
}

fun MeasurementSource.note(): String = when (this) {
    MeasurementSource.ACCESSORY_CHANNEL -> "1 % precision · accessory"
    MeasurementSource.BLE_ENCRYPTED -> "1 % precision · advertisement"
    MeasurementSource.BLE_ADVERTISEMENT -> "±10 % · advertisement"
    MeasurementSource.STANDARD_BLUETOOTH -> "±10 % · Android"
}

fun BatteryReading.note(): String = source.note()

fun ModelEvidence.label(): String = when (this) {
    ModelEvidence.DEVICE_INFORMATION -> "Identified by device information"
    ModelEvidence.BLE_ADVERTISEMENT -> "Identified from its advertisement"
    ModelEvidence.REMEMBERED -> "Identified in an earlier session"
    ModelEvidence.BLUETOOTH_NAME -> "Guessed from the Bluetooth name"
    ModelEvidence.UNKNOWN -> "Model not identified"
}

fun BleMatch.label(): String = when (this) {
    BleMatch.KEY_VERIFIED -> "verified with this accessory's key"
    BleMatch.PROBABLE -> "probable match: the only compatible accessory nearby with a strong signal"
}

fun formatTime(millis: Long?): String = millis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Never"

fun relativeTime(millis: Long?, now: Long = System.currentTimeMillis()): String {
    millis ?: return "never"
    val d = (now - millis) / 1000
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86_400 -> "${d / 3600} h ago"
        else -> "${d / 86_400} d ago"
    }
}
