package br.com.exames.app.di

import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.usecase.EvaluateObservation
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import br.com.exames.domain.inference.TextInference
import br.com.exames.inferencelocal.LocalTextInference
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {
    @Provides fun assistedPolicy(): br.com.exames.domain.inference.AssistedRuntimePolicy = br.com.exames.domain.inference.AssistedRuntimePolicy {
        br.com.exames.inferencelocal.SelectedInferenceConfiguration.PRODUCTION_PROTOCOL_ACCEPTED &&
            br.com.exames.inferencelocal.SelectedInferenceConfiguration.referenceDeviceCompatible()
    }
    @Provides fun modelPreparation(@ApplicationContext context: Context,
        policy: br.com.exames.domain.inference.AssistedRuntimePolicy): br.com.exames.domain.inference.PrepareLocalInference =
        br.com.exames.domain.inference.PrepareLocalInference {
            if (!br.com.exames.inferencelocal.SelectedInferenceConfiguration.referenceDeviceCompatible())
                br.com.exames.domain.inference.ModelReadiness.UNSUPPORTED_DEVICE
            else try {
                br.com.exames.inferencelocal.BundledModelStore(context).prepare()
                if (policy.isEnabled()) br.com.exames.domain.inference.ModelReadiness.READY else br.com.exames.domain.inference.ModelReadiness.VERIFICATION_PENDING
            } catch (failure: br.com.exames.inferencelocal.ModelPreparationException) {
                when (failure.reason) {
                    br.com.exames.inferencelocal.ModelPreparationFailure.MISSING_ASSET -> br.com.exames.domain.inference.ModelReadiness.MISSING
                    br.com.exames.inferencelocal.ModelPreparationFailure.CORRUPT_ASSET -> br.com.exames.domain.inference.ModelReadiness.CORRUPT
                    br.com.exames.inferencelocal.ModelPreparationFailure.INSUFFICIENT_SPACE -> br.com.exames.domain.inference.ModelReadiness.INSUFFICIENT_SPACE
                    br.com.exames.inferencelocal.ModelPreparationFailure.STORAGE_UNAVAILABLE -> br.com.exames.domain.inference.ModelReadiness.STORAGE_UNAVAILABLE
                }
            }
        }
    @Provides fun corpusIntake(@ApplicationContext context: Context, importer: br.com.exames.datalocal.ProtectedDocumentImporter): br.com.exames.featureimport.CorpusIntake =
        br.com.exames.featureimport.CorpusIntake { uri, onImported -> br.com.exames.datalocal.ProtectedCorpusImporter(context.contentResolver, importer).import(uri, onImported) }
    @Provides fun reviewActions(repository: br.com.exames.domain.validation.ValidationRepository,
        start: br.com.exames.domain.usecase.StartProcessingRun): br.com.exames.featureresults.ReviewActions =
        object : br.com.exames.featureresults.ReviewActions {
            override suspend fun correct(runId: String, observationId: String, field: br.com.exames.domain.model.CorrectionField,
                old: String?, value: String?, id: String) = br.com.exames.domain.usecase.SaveExtractionCorrection(repository)
                .save(runId, observationId, field, old, value, id)
            override suspend fun reevaluate(parentId: String, id: String) = br.com.exames.domain.usecase.ReevaluateRun(repository,
                br.com.exames.domain.usecase.ProcessingEnvironment(android.os.Build.MODEL, android.os.Build.VERSION.RELEASE)).reevaluate(parentId, id)
            override suspend fun revise(documentId: String, parentHash: String, draft: String) {
                br.com.exames.domain.usecase.ReviseGroundTruth(repository).revise(documentId, parentHash, draft)
            }
            override suspend fun reextract(documentId: String, parentId: String, id: String) =
                start.start(documentId, id, br.com.exames.domain.validation.ExtractionMode.BASELINE, parentId).run
            override suspend fun compare(runId: String, truthHash: String) {
                br.com.exames.domain.validation.MeasureValidation(repository).measure(runId, truthHash)
            }
            override suspend fun report(runId: String, truthHash: String): br.com.exames.domain.validation.LocalValidationReport {
                val run = requireNotNull(repository.findRun(runId))
                val case = requireNotNull(repository.find(run.documentId)?.corpusCase)
                return br.com.exames.domain.validation.GenerateLocalValidationReport(repository)
                    .generate(listOf(br.com.exames.domain.validation.ReportSelection(case, runId, truthHash)))
            }
            override suspend fun pair(baselineId: String, assistedId: String, truthHash: String) =
                br.com.exames.domain.validation.CompareExtractionModes(repository).compare(baselineId, assistedId, truthHash)
        }
    @Provides fun documentIntake(importer: br.com.exames.datalocal.ProtectedDocumentImporter): br.com.exames.featureimport.DocumentIntake =
        br.com.exames.featureimport.DocumentIntake { uri -> importer.import(uri) }
    @Provides fun sourcePreview(loader: br.com.exames.datalocal.PrivatePageLoader): br.com.exames.featureresults.SourcePreviewLoader =
        br.com.exames.featureresults.SourcePreviewLoader { documentId, page ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loader.load(documentId, page).use { source ->
                    // UI owns an immutable bounded copy; the authenticated decoder's resources close now.
                    checkNotNull(source.bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)) { "PREVIEW_UNAVAILABLE" }
                }
            }
        }
    @Provides fun processBiochemistryPage(inference: TextInference, parser: br.com.exames.domain.parser.BiochemistryParser,
        evaluator: DeterministicRangeEvaluator): br.com.exames.domain.usecase.ProcessBiochemistryPage =
        br.com.exames.domain.usecase.ProcessBiochemistryPage(inference, parser, evaluator)
    @Provides fun validationReport(repository: br.com.exames.domain.validation.ValidationRepository): br.com.exames.domain.validation.GenerateLocalValidationReport =
        br.com.exames.domain.validation.GenerateLocalValidationReport(repository)
    @Provides fun validationMeasurement(repository: br.com.exames.domain.validation.ValidationRepository): br.com.exames.domain.validation.MeasureValidation =
        br.com.exames.domain.validation.MeasureValidation(repository)
    @Provides @Singleton fun inference(@ApplicationContext context: Context): TextInference = LocalTextInference(context)
    @Provides fun evaluator(): DeterministicRangeEvaluator = DeterministicRangeEvaluator()
    @Provides fun evaluate(evaluator: DeterministicRangeEvaluator): EvaluateObservation = EvaluateObservation(evaluator)
}
