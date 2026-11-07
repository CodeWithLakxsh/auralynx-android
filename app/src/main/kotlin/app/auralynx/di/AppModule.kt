package app.auralynx.di

import app.auralynx.core.common.ApplicationScope
import app.auralynx.core.common.Clock
import app.auralynx.core.common.DefaultDispatcher
import app.auralynx.core.common.IoDispatcher
import app.auralynx.core.automation.AutomationEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun clock(): Clock = Clock.System
    @Provides @IoDispatcher fun io(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DefaultDispatcher fun cpu(): CoroutineDispatcher = Dispatchers.Default
    @Provides @Singleton @ApplicationScope fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Provides @Singleton fun automationEngine(): AutomationEngine = AutomationEngine()
}
