package br.com.exames.domain.usecase

import br.com.exames.domain.model.Document
import br.com.exames.domain.validation.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ReviseGroundTruthTest {
    private val truth = ValidationJson.format.decodeFromString<List<GroundTruthVersion>>(
        javaClass.getResourceAsStream("/validation/truth-v2-scenarios.json")!!.bufferedReader().use { it.readText() }).first()
    private fun json(value: GroundTruthVersion = truth) = ValidationJson.format.encodeToString(value)
    @Test fun independentlyAuthoredAnswersAndCanonicalInputsArePreservedExactly() {
        assertEquals(truth, GroundTruthDraft.decode(json(), 20))
        val pending = truth.copy(reviewState = ReviewState.PENDING, reviewerId = null, reviewedAt = null)
        assertEquals(pending, GroundTruthDraft.decode(json(pending), 20))
        val legacy = truth.copy(schemaVersion = "1.0", observations = truth.observations.map { it.copy(evaluationInput = null) })
        assertEquals(legacy, GroundTruthDraft.decode(json(legacy), 20))
    }
    @Test fun duplicatesIncludingEscapedKeysAndExcessiveDepthAreRejected() {
        for (draft in listOf(json().replaceFirst("{", "{\"version\":\"other\","),
            json().replaceFirst("{", "{\"vers\\u0069on\":\"other\","), "[".repeat(26) + "null" + "]".repeat(26),
            " ".repeat(GroundTruthDraft.MAX_BYTES + 1), json() + "{}", json().replaceFirst("{", "{\"unknown\":1,"))) {
            assertThrows(IllegalArgumentException::class.java) { GroundTruthDraft.decode(draft, 20) }
        }
    }
    @Test fun invalidMetadataCanonicalOwnershipNumbersAndReviewStatesAreRejected() {
        val first = truth.observations.first()
        val invalidRows = listOf(first.copy(numericValue = "1,5"), first.copy(low = "1e5"),
            first.copy(numericValue = "1".repeat(513)), first.copy(evaluationInput = null),
            first.copy(evaluationInput = first.evaluationInput!!.copy(selectedReference = first.evaluationInput.selectedReference!!.copy(observationId = "foreign"))))
        val invalid = invalidRows.map { truth.copy(observations = listOf(it)) } + listOf(
            truth.copy(observations = listOf(first, first)), truth.copy(observations = emptyList()), truth.copy(schemaVersion = "3"),
            truth.copy(reviewedAt = "invalid"), truth.copy(reviewerId = ""), truth.copy(reviewState = ReviewState.PENDING))
        for (candidate in invalid) assertThrows(IllegalArgumentException::class.java) { GroundTruthDraft.decode(json(candidate), 20) }
        assertThrows(IllegalArgumentException::class.java) { GroundTruthDraft.decode(json(), 0) }
    }
    @Test fun revisionHasRealHashParentAndUniqueVersionWithoutReadingAnyExtractionOrComparison() = runBlocking {
        val repo = mockk<ValidationRepository>(); val parent = GroundTruthSnapshot("a".repeat(64), truth)
        val stored = mutableListOf(parent)
        coEvery { repo.find("document") } returns Document("document", "artifact:original", "c".repeat(64), "application/pdf", Instant.EPOCH, 20)
        coEvery { repo.groundTruth("document") } answers { stored.toList() }
        coEvery { repo.appendGroundTruth("document", any()) } coAnswers { stored += secondArg<GroundTruthSnapshot>() }
        val changed = truth.copy(version = "independent-review-2")
        val revision = ReviseGroundTruth(repo).revise("document", parent.sha256, json(changed))
        assertEquals(parent.sha256, revision.parentSha256)
        assertEquals(changed, revision.groundTruth)
        assertNotEquals(parent.sha256, revision.sha256)
        assertEquals(revision, ReviseGroundTruth(repo).revise("document", parent.sha256, json(changed)))
        assertEquals(listOf(parent, revision), stored)
        coVerify(exactly = 1) { repo.appendGroundTruth(any(), any()) }
        coVerify(exactly = 0) { repo.observations(any()); repo.assessments(any()); repo.createRun(any()); repo.appendComparison(any()); repo.appendCorrection(any()) }
        for (candidate in listOf(changed.copy(caseId = "foreign"), truth, changed.copy(reviewState = ReviewState.PENDING, reviewerId = null, reviewedAt = null))) {
            try { ReviseGroundTruth(repo).revise("document", parent.sha256, json(candidate)); fail("Conflicting version accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(2, stored.size)
    }
}
