package app.auralynx.core.data.di

import android.content.Context
import androidx.room.Room
import app.auralynx.core.data.db.AuralynxDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AuralynxDatabase =
        Room.databaseBuilder(context, AuralynxDatabase::class.java, AuralynxDatabase.NAME).build()
}
