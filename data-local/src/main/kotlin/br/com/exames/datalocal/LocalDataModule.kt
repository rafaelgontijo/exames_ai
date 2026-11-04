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
import br.com.exames.datadocuments.SafDocumentReader
import br.com.exames.datadocuments.AuthenticatedPdfSource
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.domain.parser.BiochemistryParser
import java.io.IOException

@Module
@InstallIn(SingletonComponent::class)
object LocalDataModule {
    @Provides @Singleton fun startRun(repository: ValidationRepository,
        scheduler: br.com.exames.domain.usecase.RunWorkScheduler, pages: PrivateRunPages): br.com.exames.domain.usecase.StartProcessingRun =
        br.com.exames.domain.usecase.StartProcessingRun(repository, scheduler, pages,
            br.com.exames.domain.usecase.ProcessingVersions(PrivateRunPages.VERSION, BiochemistryParser.VERSION,
                br.com.exames.domain.evaluator.DeterministicRangeEvaluator.RULE_VERSION),
            br.com.exames.domain.usecase.ProcessingEnvironment(android.os.Build.MODEL, android.os.Build.VERSION.RELEASE),
            inference = pages::inferenceConfiguration)
    @Provides @Singleton fun runScheduler(@ApplicationContext context: Context): br.com.exames.domain.usecase.RunWorkScheduler =
        AndroidRunWorkScheduler(context)
    @Provides @Singleton fun runControls(repository: ValidationRepository, scheduler: br.com.exames.domain.usecase.RunWorkScheduler,
        pages: PrivateRunPages): br.com.exames.domain.usecase.ManageProcessingRuns = br.com.exames.domain.usecase.ManageProcessingRuns(
            repository, scheduler, pages, br.com.exames.domain.usecase.ProcessingEnvironment(android.os.Build.MODEL, android.os.Build.VERSION.RELEASE))
    @Provides @Singleton fun runProcessor(repository: ValidationRepository, pages: PrivateRunPages): br.com.exames.domain.usecase.ResumeProcessingRun =
        br.com.exames.domain.usecase.ResumeProcessingRun(repository, pages)
    @Provides @Singleton fun runPages(loader: PrivatePageLoader, pdf: PdfPreviewRenderer,
        processor: br.com.exames.domain.usecase.ProcessBiochemistryPage,
        repository: ValidationRepository, @ApplicationContext context: Context,
        policy: br.com.exames.domain.inference.AssistedRuntimePolicy): PrivateRunPages = PrivateRunPages(loader, pdf, processor, attempts = repository,
            sampler = { PageSampler(context) }, assistedCompatible = { policy.isEnabled() })
    @Provides fun parser(): BiochemistryParser = BiochemistryParser()
    @Provides @Singleton fun databaseFactory(@ApplicationContext context: Context): ProtectedDatabaseFactory =
        ProtectedDatabaseFactory(context)
    @Provides @Singleton fun validationRepository(factory: ProtectedDatabaseFactory,
        scheduler: br.com.exames.domain.usecase.RunWorkScheduler, @ApplicationContext context: Context): ValidationRepository =
        ProtectedValidationRepository(factory, requestCleanup = { DeletionWorker.requestCleanup(context) },
            cancelWork = { ids -> ids.forEach { scheduler.cancel(it) } })
    @Provides fun documents(repository: ValidationRepository): DocumentRepository = repository
    @Provides @Singleton fun artifacts(@ApplicationContext context: Context): EncryptedArtifactStore =
        EncryptedArtifactStore(context)
    @Provides @Singleton fun importer(@ApplicationContext context: Context,
        artifacts: EncryptedArtifactStore, documents: DocumentRepository): ProtectedDocumentImporter =
        ProtectedDocumentImporter(context, SafDocumentReader(context.contentResolver), artifacts, documents)
    @Provides @Singleton fun pdfSource(artifacts: EncryptedArtifactStore,
        documents: DocumentRepository): AuthenticatedPdfSource = AuthenticatedPdfSource { id ->
        val (document, bytes) = try { readOwnedDocument(id, documents, artifacts) }
            catch (_: java.io.IOException) { throw IOException("PDF_UNAVAILABLE") }
        if (document.mimeType != "application/pdf") {
            bytes.fill(0)
            throw IOException("PDF_FORMAT_INVALID")
        }
        bytes
    }
    @Provides @Singleton fun pdfReader(@ApplicationContext context: Context,
        source: AuthenticatedPdfSource): PdfPreviewRenderer = PdfPreviewRenderer(context, source)
    @Provides @Singleton fun pages(documents: DocumentRepository, artifacts: EncryptedArtifactStore,
        pdf: PdfPreviewRenderer): PrivatePageLoader = PrivatePageLoader(documents, artifacts, pdf)
}
