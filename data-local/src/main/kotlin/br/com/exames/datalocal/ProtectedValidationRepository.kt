package br.com.exames.datalocal

import androidx.room.withTransaction
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import kotlinx.coroutines.Dispatchers
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
) : ValidationRepository {
    private val json = ValidationJson.format
    private val codec get() = factory.codec

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
        check(dao.pendingDeletions().none { it.documentId == id }) { "Document is being deleted" }
        return checkNotNull(dao.document(id)) { "Document is unavailable" }
    }

    override suspend fun save(document: Document) = withContext(Dispatchers.IO) {
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            check(dao.pendingDeletions().none { it.documentId == document.id }) { "Document is being deleted" }
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
        factory.open().records().deleteCompletely(id)
        // Periodic reconciliation remains a fallback if immediate scheduling is unavailable.
        runCatching(requestCleanup)
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

    override suspend fun commitPage(batch: PageBatch): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = batch.checkpoint
        val db = factory.open()
        db.withTransaction {
            val dao = db.records()
            val row = checkNotNull(dao.run(checkpoint.runId)) { "Run unavailable" }
            val document = ModelPayloads.document(unpackObject("document", row.documentId, eligible(dao, row.documentId).payload))
            val run: ProcessingRun = unpack("run", row.id, row.payload)
            require(run.mode == checkpoint.mode && run.state == RunState.RUNNING)
            require(checkpoint.page >= 1)
            val pageCount = document.pageCount
            require(pageCount == null || checkpoint.page <= pageCount)
            require(checkpoint.state == PageState.COMPLETE ||
                (batch.observations.isEmpty() && batch.references.isEmpty() && batch.assessments.isEmpty()))
            if (dao.checkpoints(run.runId).any { it.page == checkpoint.page && it.state == PageState.COMPLETE.name })
                return@withTransaction false
            val ids = batch.observations.map { it.id }.toSet()
            require(ids.size == batch.observations.size)
            require(batch.observations.all { it.documentId == run.documentId && it.page == checkpoint.page })
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
            val id = StableIds.of("checkpoint", run.runId, checkpoint.page.toString())
            dao.checkpoint(ProtectedPageRow(run.runId, checkpoint.page, checkpoint.state.name, pack("checkpoint", id, checkpoint)))
            true
        }
    }

    override suspend fun observations(runId: String): List<Observation> = withContext(Dispatchers.IO) {
        factory.open().records().observations(runId).map { ModelPayloads.observation(unpackObject("observation", it.id, it.payload)) }
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
                CorrectionField.REFERENCE -> reference?.referenceRaw
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
}
