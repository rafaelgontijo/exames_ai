package br.com.exames.datalocal

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalDataModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "exames.db").build()
    @Provides fun observations(db: AppDatabase): ObservationDao = db.observationDao()
    @Provides fun documents(db: AppDatabase): DocumentDao = db.documentDao()
    @Provides @Singleton fun artifacts(@ApplicationContext context: Context): EncryptedArtifactStore =
        EncryptedArtifactStore(context)
}
