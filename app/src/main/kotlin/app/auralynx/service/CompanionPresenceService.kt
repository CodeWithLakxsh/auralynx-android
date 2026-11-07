package app.auralynx.service

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import app.auralynx.core.common.Diag
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Bound by the system when a linked (CompanionDeviceManager-associated) accessory comes into range.
 * Companion apps are exempt from background foreground-service restrictions, which is the
 * supported way for Auralynx to start monitoring without the user opening the app.
 */
@RequiresApi(31)
@AndroidEntryPoint
class CompanionPresenceService : CompanionDeviceService() {
    @Inject lateinit var coordinator: AccessoryCoordinator

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        Diag.i(TAG) { "Companion device appeared" }
        coordinator.start()
        MonitorService.start(this)
    }

    @Deprecated("Replaced by onDeviceAppeared(AssociationInfo) on API 33")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT >= 33) return
        coordinator.start()
        MonitorService.start(this)
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = Unit

    @Deprecated("Replaced by onDeviceDisappeared(AssociationInfo) on API 33")
    override fun onDeviceDisappeared(address: String) = Unit

    companion object { private const val TAG = "CompanionPresence" }
}
