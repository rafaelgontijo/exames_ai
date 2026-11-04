package br.com.exames.datalocal

import androidx.room.withTransaction
import br.com.exames.domain.model.*
import br.com.exames.domain.inference.*
import br.com.exames.domain.validation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/** Storage never classifies data or substitutes a corrected output for an original run. */
class ProtectedValidationRepository(
    private val factory: ProtectedDatabaseFactory,
    private val requestCleanup: () -> Unit = {},
    private val cancelWork: suspend (List<String>) -> Unit = {},
) : ValidationRepository {
    private val json = ValidationJson.format
    private val codec get() = factory.codec
    override suspend fun importCorpusDocument(document: Document, truth: GroundTruthSnapshot) {
        val case = requireNotNull(document.corpusCase)
        require(case.documentSha256 == document.hash && case.caseId == truth.groundTruth.caseId && case.groundTruthSha256 == truth.sha256)
        val db = factory.open()
        db.withTransaction {
            require(db.records().document(document.id) == null)
            save(document)
            appendGroundTruth(document.id, truth)
        }
    }

    private fun packObject(type: String, id: String, value: JsonObject) =
        codec.encrypt(type, id, "2", value.toString().toByteArray(Charsets.UTF_8))
    private fun unpackObject(type: String, id: String, payload: ByteArray) =
        json.parseToJsonElement(codec.decrypt(type, id, "2", payload).toString(Charsets.UTF_8)).jsonObject
    private inline fun <reified T> pack(type: String, id: String, value: T) =
        codec.encrypt(type, id, "2", json.encodeToString(value).toByteArray(Charsets.UTF_8))
    private inline fun <reified T> unpack(type: String, id: String, payload: ByteArray): T =
        json.decodeFromString(codec.decrypt(type, id, "2", payload).toString(Charsets.UTF_8))

    override fun observeDocuments(): Flow<List<Document>> = flow {
        emitAll(factory.open().records().observeDocuments().map { rows ->
            rows.map { ModelPayloads.document(unpackObject("document", it.id, it.payload)) }
        })
    }.flowOn(Dispatchers.IO)

    override suspend fun find(id: String): Document? = withContext(Dispatchers.IO) {
        factory.open().records().document(id)?.let { ModelPayloads.document(unpackObject("document", id, it.payload)) }
    }

    private suspend fun eligible(dao: ProtectedDao, id: String): ProtectedDocumentRow {
        check(dao.tombstone(id) == null) { "Document is being deleted" }
        return checkNotNull(dao.document(id)) { "Document is unavailable" }
    }

    override suspend fun save(document: Document) = withContext(Dispatchers.IO) {
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            check(dao.tombstone(document.id) == null) { "Document is being deleted" }
            val existing = dao.document(document.id)
            val row = ProtectedDocumentRow(document.id, document.importedAt.toEpochMilli(),
                packObject("document", document.id, ModelPayloads.document(document)))
            if (existing == null) dao.insertDocument(row) else {
                val before = ModelPayloads.document(unpackObject("document", document.id, existing.payload))
                require(before.copy(pageCount = document.pageCount, processingState = document.processingState) == document) {
                    "Original document identity and source are immutable"
                }
                dao.updateDocument(row)
            }
        }
    }

    override suspend fun deleteCompletely(id: String) = withContext(Dispatchers.IO) {
        val dao = factory.open().records()
        dao.deleteCompletely(id)
        withContext(NonCancellable) {
            // Intent is already durable. Outbox retries cancellation if WorkManager is unavailable here.
            runCatching { cancelWork(dao.deletionRunIds(id)) }
            runCatching(requestCleanup)
        }
        Unit
    }

    override fun observeRuns(documentId: String): Flow<List<ProcessingRun>> = flow {
        emitAll(factory.open().records().observeRuns(documentId).map { rows ->
            rows.map { unpack<ProcessingRun>("run", it.id, it.payload) }
        })
    }.flowOn(Dispatchers.IO)

    override suspend fun createRun(run: ProcessingRun) = withContext(Dispatchers.IO) {
        require(run.state in setOf(RunState.QUEUED, RunState.RUNNING))
        require((run.mode == ExtractionMode.ASSISTED) == (run.inference != null))
        require(run.outputBasis != OutputBasis.AUTOMATIC || (run.correctionIds.isEmpty() && run.projectionVersion == null))
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            val document = ModelPayloads.document(unpackObject("document", run.documentId, eligible(dao, run.documentId).payload))
            require(document.hash == run.documentSha256)
            run.parentRunId?.let {
                val parent = requireNotNull(dao.run(it))
                require(parent.documentId == run.documentId)
                val parentRun: ProcessingRun = unpack("run", parent.id, parent.payload)
                require(parentRun.caseId == null || parentRun.caseId == run.caseId)
            }
            dao.insertRun(ProtectedRunRow(run.runId, run.documentId, run.state.name,
                Instant.parse(run.startedAt).toEpochMilli(), pack("run", run.runId, run)))
        }
    }

    override suspend fun findRun(runId: String): ProcessingRun? = withContext(Dispatchers.IO) {
        factory.open().records().run(runId)?.let { unpack<ProcessingRun>("run", it.id, it.payload) }
    }

    override suspend fun recoverableRuns(): List<ProcessingRun> = withContext(Dispatchers.IO) {
        factory.open().records().recoverableRuns().map { unpack<ProcessingRun>("run", it.id, it.payload) }
    }

    override suspend fun stopRunIfActive(runId: String, state: RunState, failure: RunFailure?): ProcessingRun? = withContext(Dispatchers.IO) {
        require(state == RunState.CANCELLED && failure == null || state == RunState.FAILED && failure != null)
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            val row = dao.run(runId) ?: return@withTransaction null
            val source: ProcessingRun = unpack("run", row.id, row.payload)
            if (source.state !in setOf(RunState.QUEUED, RunState.RUNNING)) return@withTransaction source
            eligible(dao, row.documentId)
            val outcome = if (source.mode == ExtractionMode.BASELINE) InferenceOutcome.NOT_ATTEMPTED
                else if (state == RunState.CANCELLED) InferenceOutcome.CANCELLED else InferenceOutcome.FAILED
            val next = source.copy(state = state, inferenceOutcome = outcome, failure = failure)
            dao.updateRunState(runId, state.name, pack("run", runId, next))
            // Also cover cancellation after page processing returned but before commitPage.
            val now = Instant.now().toString()
            for (page in dao.checkpoints(runId)) {
                val id = StableIds.of("checkpoint", runId, page.page.toString())
                val checkpoint: PageCheckpoint = unpack("checkpoint", id, page.payload)
                if (checkpoint.inferenceHistory.none { it.state == InferenceAttemptState.STARTED }) continue
                val stopped = checkpoint.copy(state = if (state == RunState.CANCELLED) PageState.CANCELLED else PageState.FAILED,
                    errorCode = if (state == RunState.CANCELLED) "RUN_CANCELLED" else "RUN_FAILED",
                    inferenceOutcome = outcome, inferenceAttempt = null, inferenceAttemptId = null,
                    inferenceHistory = checkpoint.inferenceHistory.map { if (it.state != InferenceAttemptState.STARTED) it
                        else it.copy(state = if (state == RunState.CANCELLED) InferenceAttemptState.CANCELLED
                            else InferenceAttemptState.INTERRUPTED, endedAt = now) })
                dao.checkpoint(ProtectedPageRow(runId, page.page, stopped.state.name, pack("checkpoint", id, stopped)))
            }
            next
        }
    }

    override suspend fun updateRunState(runId: String, state: RunState, outcome: InferenceOutcome) = withContext(Dispatchers.IO) {
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            val row = checkNotNull(dao.run(runId)) { "Run unavailable" }
            val source: ProcessingRun = unpack("run", row.id, row.payload)
            val document = ModelPayloads.document(unpackObject("document", row.documentId, eligible(dao, row.documentId).payload))
            if (source.state == state && source.inferenceOutcome == outcome) return@withTransaction
            val allowed = when (source.state) {
                RunState.QUEUED -> setOf(RunState.RUNNING, RunState.FAILED, RunState.CANCELLED)
                RunState.RUNNING -> setOf(RunState.COMPLETE, RunState.PARTIAL, RunState.FAILED, RunState.CANCELLED)
                else -> emptySet()
            }
            require(state in allowed) { "Invalid run state transition" }
            if (state == RunState.COMPLETE) {
                val pages = requireNotNull(document.pageCount) { "Page count unresolved" }
                val checkpoints = dao.checkpoints(runId)
                require(pages > 0 && checkpoints.size == pages && checkpoints.all { it.state == PageState.COMPLETE.name }
                    && checkpoints.map { it.page }.toSet() == (1..pages).toSet()) { "Run still has incomplete pages" }
            }
            require(source.mode == ExtractionMode.ASSISTED || outcome == InferenceOutcome.NOT_ATTEMPTED)
            val next = source.copy(state = state, inferenceOutcome = outcome)
            dao.updateRunState(runId, state.name, pack("run", runId, next))
        }
    }

    override suspend fun startInferenceAttempt(runId: String, page: Int, attemptId: String) = withContext(Dispatchers.IO) {
        require(ProcessingRunIds.isOpaque(attemptId))
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            val row = checkNotNull(dao.run(runId)) { "RUN_UNAVAILABLE" }
            val document = ModelPayloads.document(unpackObject("document", row.documentId, eligible(dao, row.documentId).payload))
            val run: ProcessingRun = unpack("run", runId, row.payload)
            require(run.state == RunState.RUNNING && run.mode == ExtractionMode.ASSISTED)
            val configuration = requireNotNull(run.inference)
            require(page >= 1 && (document.pageCount?.let { page <= it } ?: true))
            val id = StableIds.of("checkpoint", runId, page.toString())
            val old = dao.checkpoints(runId).singleOrNull { it.page == page }?.let {
                unpack<PageCheckpoint>("checkpoint", id, it.payload)
            }
            require(old?.state != PageState.COMPLETE) { "PAGE_ALREADY_COMPLETE" }
            val history = old?.inferenceHistory.orEmpty()
            history.singleOrNull { it.attemptId == attemptId }?.let {
                require(it.state == InferenceAttemptState.STARTED && it.configuration == configuration)
                return@withTransaction // Retry of an uncertain journal write is idempotent.
            }
            require(history.size < InferenceAttemptRecord.MAX_PER_PAGE) { "INFERENCE_ATTEMPT_LIMIT" }
            val now = Instant.now().toString()
            val next = history.map { if (it.state == InferenceAttemptState.STARTED)
                it.copy(state = InferenceAttemptState.INTERRUPTED, endedAt = now) else it } +
                InferenceAttemptRecord(attemptId, configuration, now)
            val checkpoint = PageCheckpoint(runId, page, run.mode, PageState.PENDING, null, inferenceHistory = next)
            dao.checkpoint(ProtectedPageRow(runId, page, checkpoint.state.name, pack("checkpoint", id, checkpoint)))
        }
    }

    override suspend fun cancelInferenceAttempt(runId: String, page: Int, attemptId: String) = withContext(Dispatchers.IO) {
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            val row = dao.run(runId) ?: return@withTransaction
            if (dao.tombstone(row.documentId) != null) return@withTransaction
            val run: ProcessingRun = unpack("run", runId, row.payload)
            if (run.state !in setOf(RunState.RUNNING, RunState.CANCELLED)) return@withTransaction
            val id = StableIds.of("checkpoint", runId, page.toString())
            val oldRow = dao.checkpoints(runId).singleOrNull { it.page == page } ?: return@withTransaction
            val old: PageCheckpoint = unpack("checkpoint", id, oldRow.payload)
            if (old.state == PageState.COMPLETE || old.inferenceHistory.none {
                it.attemptId == attemptId && it.state == InferenceAttemptState.STARTED }) return@withTransaction
            val now = Instant.now().toString()
            val next = old.copy(state = if (run.state == RunState.CANCELLED) PageState.CANCELLED else PageState.PENDING,
                inferenceOutcome = InferenceOutcome.CANCELLED,
                inferenceHistory = old.inferenceHistory.map { if (it.attemptId == attemptId)
                    it.copy(state = InferenceAttemptState.CANCELLED, endedAt = now) else it })
            dao.checkpoint(ProtectedPageRow(runId, page, next.state.name, pack("checkpoint", id, next)))
        }
    }

    override suspend fun commitPage(batch: PageBatch): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = batch.checkpoint
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            val row = checkNotNull(dao.run(checkpoint.runId)) { "Run unavailable" }
            val document = ModelPayloads.document(unpackObject("document", row.documentId, eligible(dao, row.documentId).payload))
            val run: ProcessingRun = unpack("run", row.id, row.payload)
            require(run.mode == checkpoint.mode && run.state == RunState.RUNNING)
            checkpoint.inferenceAttempt?.let { attempt ->
                require(attempt.configuration == run.inference && attempt.outcome == checkpoint.inferenceOutcome) {
                    "INFERENCE_CHECKPOINT_MISMATCH"
                }
                require((run.mode == ExtractionMode.BASELINE) == (attempt.outcome == InferenceOutcome.NOT_ATTEMPTED)) {
                    "INFERENCE_MODE_MISMATCH"
                }
            }
            require(checkpoint.page >= 1)
            val pageCount = document.pageCount
            require(pageCount == null || checkpoint.page <= pageCount)
            require(checkpoint.state == PageState.COMPLETE ||
                (batch.observations.isEmpty() && batch.references.isEmpty() && batch.assessments.isEmpty()))
            val oldRow = dao.checkpoints(run.runId).singleOrNull { it.page == checkpoint.page }
            if (oldRow?.state == PageState.COMPLETE.name) return@withTransaction false
            val checkpointId = StableIds.of("checkpoint", run.runId, checkpoint.page.toString())
            val old: PageCheckpoint? = oldRow?.let { unpack("checkpoint", checkpointId, it.payload) }
            val history = old?.inferenceHistory.orEmpty()
            require(checkpoint.inferenceHistory.isEmpty() || checkpoint.inferenceHistory == history) { "INFERENCE_HISTORY_IMMUTABLE" }
            val attemptId = checkpoint.inferenceAttemptId
            if (attemptId != null) {
                val started = requireNotNull(history.singleOrNull { it.attemptId == attemptId }) { "INFERENCE_INTENT_MISSING" }
                require(started.state == InferenceAttemptState.STARTED && started.configuration == run.inference)
                require(checkpoint.inferenceAttempt?.requestSubmitted == true)
            } else require(checkpoint.inferenceAttempt?.requestSubmitted != true) { "INFERENCE_INTENT_MISSING" }
            val now = Instant.now().toString()
            val persistedCheckpoint = checkpoint.copy(inferenceHistory = history.map {
                if (it.state != InferenceAttemptState.STARTED) it
                else if (it.attemptId == attemptId) it.copy(state = InferenceAttemptState.COMPLETED,
                    endedAt = now, result = checkpoint.inferenceAttempt)
                else it.copy(state = InferenceAttemptState.INTERRUPTED, endedAt = now)
            })
            val ids = batch.observations.map { it.id }.toSet()
            require(ids.size == batch.observations.size)
            require(batch.observations.all { it.documentId == run.documentId && it.page == checkpoint.page })
            require(batch.observations.all { observation ->
                observation.referenceCandidates.all { it.observationId == observation.id && it.sourcePage >= 1 &&
                    (pageCount == null || it.sourcePage <= pageCount) } &&
                    observation.fieldEvidence.all { pageCount == null || it.region.page <= pageCount }
            })
            require(batch.references.all { it.observationId in ids } && batch.references.map { it.observationId }.toSet().size == batch.references.size)
            require(batch.assessments.map { it.observationId }.toSet() == ids && batch.assessments.size == ids.size)
            require(batch.assessments.all { it.ruleVersion == run.ruleVersion })
            dao.insertObservations(batch.observations.mapIndexed { index, observation ->
                ProtectedObservationRow(observation.id, run.documentId, run.runId, checkpoint.page, index,
                    packObject("observation", observation.id, ModelPayloads.observation(observation)))
            })
            batch.references.forEach { dao.insertReference(ProtectedReferenceRow(it.observationId,
                packObject("reference", it.observationId, ModelPayloads.reference(it)))) }
            batch.assessments.forEach {
                val id = StableIds.of("assessment", run.runId, it.observationId)
                dao.insertAssessment(ProtectedAssessmentRow(id, it.observationId, run.runId,
                    packObject("assessment", id, ModelPayloads.assessment(it))))
            }
            dao.checkpoint(ProtectedPageRow(run.runId, checkpoint.page, checkpoint.state.name,
                pack("checkpoint", checkpointId, persistedCheckpoint)))
            true
        }
    }

    override suspend fun observations(runId: String): List<Observation> = withContext(Dispatchers.IO) {
        factory.open().records().observations(runId).map { ModelPayloads.observation(unpackObject("observation", it.id, it.payload)) }
    }
    override suspend fun commitReevaluation(run: ProcessingRun, pages: List<PageBatch>): Boolean = withContext(Dispatchers.IO) {
        require(run.outputBasis != OutputBasis.AUTOMATIC && ProcessingRunIds.isOpaque(run.runId))
        require(run.state in setOf(RunState.COMPLETE, RunState.PARTIAL, RunState.FAILED))
        require(run.inferenceOutcome == InferenceOutcome.NOT_ATTEMPTED && run.projectionVersion == br.com.exames.domain.usecase.CorrectedObservationProjection.VERSION)
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            eligible(dao, run.documentId)
            dao.run(run.runId)?.let { existing ->
                require(unpack<ProcessingRun>("run", run.runId, existing.payload) == run) { "REEVALUATION_ID_CONFLICT" }
                return@withTransaction false
            }
            val parentRow = requireNotNull(dao.run(requireNotNull(run.parentRunId)))
            val parent: ProcessingRun = unpack("run", parentRow.id, parentRow.payload)
            require(parent.state !in setOf(RunState.QUEUED, RunState.RUNNING) && parent.documentId == run.documentId &&
                parent.documentSha256 == run.documentSha256 && parent.mode == run.mode && parent.inference == run.inference &&
                parent.caseId == run.caseId && parent.extractorVersion == run.extractorVersion && parent.parserVersion == run.parserVersion)
            require(run.ruleVersion == br.com.exames.domain.evaluator.DeterministicRangeEvaluator.RULE_VERSION)
            val originals = dao.observations(parent.runId)
            val latestIds = (parent.correctionIds + originals.flatMap { row ->
                dao.corrections(row.id).map { it.id }
            }).distinct()
            require(run.correctionIds == latestIds && (run.outputBasis == OutputBasis.CORRECTED) == latestIds.isNotEmpty()) { "REEVALUATION_CORRECTIONS_CHANGED" }
            val documentRow = eligible(dao, run.documentId)
            val document = ModelPayloads.document(unpackObject("document", run.documentId, documentRow.payload))
            val count = requireNotNull(document.pageCount)
            require(count in 1..20)
            require(pages.size == count && pages.map { it.checkpoint.page }.toSet() == (1..count).toSet())
            require(pages.all { it.checkpoint.runId == run.runId && it.checkpoint.mode == run.mode &&
                it.checkpoint.inferenceOutcome == InferenceOutcome.NOT_ATTEMPTED && it.checkpoint.inferenceAttempt == null &&
                it.checkpoint.inferenceHistory.isEmpty() && it.checkpoint.inferenceAttemptId == null &&
                it.checkpoint.durationMillis == null && it.checkpoint.measurements == null })
            createRun(run.copy(state = RunState.RUNNING))
            pages.forEach { check(commitPage(it)) }
            updateRunState(run.runId, run.state, InferenceOutcome.NOT_ATTEMPTED)
            true
        }
    }
    override suspend fun reference(observationId: String): ReferenceRange? = withContext(Dispatchers.IO) {
        factory.open().records().reference(observationId)?.let { ModelPayloads.reference(unpackObject("reference", observationId, it.payload)) }
    }
    override suspend fun assessments(runId: String): List<Assessment> = withContext(Dispatchers.IO) {
        factory.open().records().assessments(runId).map { ModelPayloads.assessment(unpackObject("assessment", it.id, it.payload)) }
    }
    override suspend fun checkpoints(runId: String): List<PageCheckpoint> = withContext(Dispatchers.IO) {
        factory.open().records().checkpoints(runId).map {
            unpack<PageCheckpoint>("checkpoint", StableIds.of("checkpoint", runId, it.page.toString()), it.payload)
        }
    }
    override fun observeCheckpoints(runId: String): Flow<List<PageCheckpoint>> = flow {
        emitAll(factory.open().records().observeCheckpoints(runId).map { rows ->
            rows.map { unpack<PageCheckpoint>("checkpoint", StableIds.of("checkpoint", runId, it.page.toString()), it.payload) }
        })
    }.flowOn(Dispatchers.IO)
    override suspend fun corrections(observationId: String): List<ManualCorrection> = withContext(Dispatchers.IO) {
        factory.open().records().corrections(observationId).map { ModelPayloads.correction(unpackObject("correction", it.id, it.payload)) }
    }
    override suspend fun appendCorrection(correction: ManualCorrection) = withContext(Dispatchers.IO) {
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            val row = checkNotNull(dao.observation(correction.observationId)) { "Observation unavailable" }
            eligible(dao, row.documentId)
            val observation = ModelPayloads.observation(unpackObject("observation", row.id, row.payload))
            val reference = dao.reference(row.id)?.let { ModelPayloads.reference(unpackObject("reference", row.id, it.payload)) }
            val previous = dao.corrections(row.id).map { ModelPayloads.correction(unpackObject("correction", it.id, it.payload)) }
                .lastOrNull { it.field == correction.field }
            val original = when (correction.field) {
                CorrectionField.ANALYTE -> observation.analyteRaw
                CorrectionField.RESULT -> observation.resultRaw
                CorrectionField.UNIT -> observation.unitRaw
                CorrectionField.REFERENCE -> observation.referenceRaw ?: reference?.referenceRaw
                CorrectionField.APPLICABILITY -> reference?.applicability?.name
            }
            require(correction.oldValue == (if (previous != null) previous.newValue else original)) { "Stale correction" }
            dao.appendCorrection(ProtectedCorrectionRow(correction.id, row.id,
                packObject("correction", correction.id, ModelPayloads.correction(correction))))
        }
    }

    override suspend fun appendGroundTruth(documentId: String, snapshot: GroundTruthSnapshot) = withContext(Dispatchers.IO) {
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            eligible(dao, documentId)
            val truth = snapshot.groundTruth
            require(truth.reviewState != ReviewState.REVIEWED || (!truth.reviewerId.isNullOrBlank() && truth.reviewedAt != null))
            snapshot.parentSha256?.let { hash ->
                val parent = dao.groundTruth(documentId).map { unpack<GroundTruthSnapshot>("ground_truth", it.id, it.payload) }
                    .singleOrNull { it.sha256 == hash } ?: error("GROUND_TRUTH_PARENT_UNAVAILABLE")
                require(parent.groundTruth.caseId == truth.caseId && parent.groundTruth.version != truth.version)
                require(sha256(json.encodeToString(truth).toByteArray()) == snapshot.sha256) { "GROUND_TRUTH_HASH_MISMATCH" }
            }
            val id = StableIds.of("ground-truth", documentId, truth.caseId, truth.version)
            dao.appendGroundTruth(ProtectedGroundTruthRow(id, documentId, truth.version, pack("ground_truth", id, snapshot)))
        }
    }
    override suspend fun groundTruth(documentId: String): List<GroundTruthSnapshot> = withContext(Dispatchers.IO) {
        factory.open().records().groundTruth(documentId).map { unpack<GroundTruthSnapshot>("ground_truth", it.id, it.payload) }
    }

    override suspend fun appendComparison(comparison: Comparison) = withContext(Dispatchers.IO) {
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            val run = checkNotNull(dao.run(comparison.runId)) { "Run unavailable" }
            eligible(dao, run.documentId)
            val runValue: ProcessingRun = unpack("run", run.id, run.payload)
            require(runValue.caseId == null || runValue.caseId == comparison.caseId)
            val truthRow = dao.groundTruth(run.documentId).singleOrNull { it.version == comparison.groundTruthVersion }
                ?: error("Ground truth version unavailable")
            val snapshot: GroundTruthSnapshot = unpack("ground_truth", truthRow.id, truthRow.payload)
            require(snapshot.groundTruth.caseId == comparison.caseId && snapshot.sha256 == comparison.groundTruthSha256)
            dao.appendComparison(ProtectedComparisonRow(comparison.comparisonId, run.documentId, run.id, truthRow.id,
                pack("comparison", comparison.comparisonId, comparison)))
        }
    }
    override suspend fun comparisons(runId: String): List<Comparison> = withContext(Dispatchers.IO) {
        factory.open().records().comparisons(runId).map { unpack<Comparison>("comparison", it.id, it.payload) }
    }
    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
