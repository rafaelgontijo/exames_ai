package br.com.exames.datalocal

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import br.com.exames.domain.repository.DocumentRepository
import br.com.exames.domain.validation.ValidationRepository

@Module
@InstallIn(SingletonComponent::class)
object LocalDataModule {
    @Provides @Singleton fun databaseFactory(@ApplicationContext context: Context): ProtectedDatabaseFactory =
        ProtectedDatabaseFactory(context)
    @Provides @Singleton fun validationRepository(factory: ProtectedDatabaseFactory,
        @ApplicationContext context: Context): ValidationRepository =
        ProtectedValidationRepository(factory) { DeletionWorker.requestCleanup(context) }
    @Provides fun documents(repository: ValidationRepository): DocumentRepository = repository
    @Provides @Singleton fun artifacts(@ApplicationContext context: Context): EncryptedArtifactStore =
        EncryptedArtifactStore(context)
}
