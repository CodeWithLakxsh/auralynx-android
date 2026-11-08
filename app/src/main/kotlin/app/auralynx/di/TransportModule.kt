package app.auralynx.di

import app.auralynx.core.bluetooth.aap.AapTransport
import app.auralynx.core.bluetooth.aap.L2capAapTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Public-API transport (the only transport in this build). */
@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {
    @Binds abstract fun transport(impl: L2capAapTransport): AapTransport
}
