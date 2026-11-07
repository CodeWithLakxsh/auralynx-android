package app.auralynx.core.automation

import app.auralynx.core.protocol.model.ListeningMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek

/** Context signals a condition can depend on. Each maps to at most one runtime permission. */
enum class Signal(val requiredPermission: RequiredPermission?) {
    TIME(null),
    ACCESSORY(RequiredPermission.BLUETOOTH),
    PROFILE(null),
    LOCATION(RequiredPermission.LOCATION),
    WIFI(RequiredPermission.LOCATION),
    FOREGROUND_APP(RequiredPermission.USAGE_ACCESS),
    ACTIVITY(RequiredPermission.ACTIVITY_RECOGNITION),
}

/** Permissions are requested only when a rule that needs them is enabled. */
enum class RequiredPermission { BLUETOOTH, LOCATION, USAGE_ACCESS, ACTIVITY_RECOGNITION }

enum class MotionActivity { STILL, WALKING }

@Serializable
sealed interface Condition {
    val signal: Signal

    /**
     * Local-time window [startMinute, endMinute) on [days]. Windows may cross midnight
     * (e.g. 22:00-07:00); the day refers to the day the window *starts*.
     */
    @Serializable @SerialName("time")
    data class TimeWindow(val startMinute: Int, val endMinute: Int, val days: Set<DayOfWeek> = DayOfWeek.entries.toSet()) : Condition {
        override val signal get() = Signal.TIME
        init {
            require(startMinute in 0 until MINUTES_PER_DAY && endMinute in 0 until MINUTES_PER_DAY) { "Minutes out of range" }
            require(startMinute != endMinute) { "A time window must not be empty" }
            require(days.isNotEmpty()) { "Select at least one day" }
        }
    }

    @Serializable @SerialName("place")
    data class AtPlace(val placeId: Long) : Condition {
        override val signal get() = Signal.LOCATION
    }

    @Serializable @SerialName("wifi")
    data class OnWifi(val ssid: String) : Condition {
        override val signal get() = Signal.WIFI
    }

    /** [address] null matches any supported accessory. */
    @Serializable @SerialName("accessory")
    data class AccessoryConnected(val address: String? = null) : Condition {
        override val signal get() = Signal.ACCESSORY
    }

    @Serializable @SerialName("app")
    data class AppInForeground(val packageName: String) : Condition {
        override val signal get() = Signal.FOREGROUND_APP
    }

    @Serializable @SerialName("activity")
    data class Activity(val activity: MotionActivity) : Condition {
        override val signal get() = Signal.ACTIVITY
    }

    /** User-selected profile such as "Studying" or "Commute". */
    @Serializable @SerialName("profile")
    data class ProfileActive(val profile: String) : Condition {
        override val signal get() = Signal.PROFILE
    }
}

/** What an action changes. At most one action per target is applied per evaluation. */
enum class ActionTarget { LISTENING_MODE, CONVERSATION_AWARENESS, AUDIO_PROFILE }

@Serializable
sealed interface Action {
    val target: ActionTarget

    @Serializable @SerialName("listening_mode")
    data class SetListeningMode(val mode: ListeningMode) : Action {
        override val target get() = ActionTarget.LISTENING_MODE
    }

    @Serializable @SerialName("conversation_awareness")
    data class SetConversationAwareness(val enabled: Boolean) : Action {
        override val target get() = ActionTarget.CONVERSATION_AWARENESS
    }

    @Serializable @SerialName("audio_profile")
    data class ApplyAudioProfile(val profileId: Long) : Action {
        override val target get() = ActionTarget.AUDIO_PROFILE
    }
}

/** A rule fires when *all* its conditions hold. */
@Serializable
data class Rule(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    /** Higher wins. */
    val priority: Int = 0,
    val conditions: List<Condition>,
    val actions: List<Action>,
    val updatedAtMillis: Long = 0,
) {
    val signals: Set<Signal> get() = conditions.mapTo(mutableSetOf()) { it.signal }
    val requiredPermissions: Set<RequiredPermission> get() = signals.mapNotNullTo(mutableSetOf()) { it.requiredPermission }
}

const val MINUTES_PER_DAY = 24 * 60
