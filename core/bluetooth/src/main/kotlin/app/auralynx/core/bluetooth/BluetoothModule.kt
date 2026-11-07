package app.auralynx.core.bluetooth

import app.auralynx.core.bluetooth.aap.AapTransport
import app.auralynx.core.bluetooth.aap.L2capAapTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BluetoothModule {
    @Binds abstract fun transport(impl: L2capAapTransport): AapTransport
}
