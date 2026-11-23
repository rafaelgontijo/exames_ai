package br.com.exames.domain.extraction

import br.com.exames.domain.inference.GenerationRequest
import br.com.exames.domain.inference.GenerationResult
import br.com.exames.domain.inference.InferenceFailure
import br.com.exames.domain.inference.TextInference
import br.com.exames.domain.parser.SourceFragment
import br.com.exames.domain.validation.ExtractionMode
import br.com.exames.domain.validation.SourceRegion
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtractExamTest {
    @Test fun linksOnlyCitationsOfTheSameRowAndRejectsInventedContent() {
        val rows = ExamEvidence.prepare(2, fragments(2, 2)).rows
        val block = ExamProtocol.blocks(rows).single()!!
        val first = rows.first()
        val second = rows.last()
        val valid = ExamProtocol.validate(block, response(
            field(2), field(1, ref = "f2"),
        ))
        assertEquals(listOf(second.name.id, first.name.id), valid.observations.map { it.name.id })
        assertEquals("adultos: < 8", valid.observations.first().reference?.text)
        assertTrue(valid.observations.last().referenceNeedsReview)
        assertEquals(null, valid.observations.last().reference)

        val referenceConflict = ExamProtocol.validate(block, response(field(1), field(1, ref = "f2")))
        assertEquals("< 7,5", referenceConflict.observations.single().result)
        assertEquals(null, referenceConflict.observations.single().reference)
        assertTrue(referenceConflict.observations.single().referenceNeedsReview)

        for (invalid in listOf(
            field(1, value = "v2"),
            field(1, value = "v99"),
            field(1, value = "n1"),
            field(1, value = null),
            field(1, result = "> 7,5"),
            field(1, result = "< 7", unit = "u2"),
            field(1, result = first.result.text, unit = null),
        )) {
            val checked = ExamProtocol.validate(block, response(invalid))
            assertTrue(checked.observations.isEmpty())
            assertTrue(checked.issue != null)
        }
    }

    @Test fun refusesExtraKeysDuplicateKeysWrongSchemaAndTrailingText() {
        val block = ExamProtocol.blocks(ExamEvidence.prepare(1, fragments(1)).rows).single()!!
        val good = response(field())
        listOf(
            good.replace(ExamProtocol.SCHEMA_VERSION, "exam/9"),
            good.replace("\"rows\":", "\"schema\":\"exam/1\",\"rows\":"),
            good.replace("\"rows\":", "\"thinking\":null,\"rows\":"),
            good + "\n{}",
            "```json\n$good\n```",
            " ".repeat(8193),
        ).forEach { assertEquals(ExamIssue.INVALID_RESPONSE, ExamProtocol.validate(block, it).issue) }
    }

    @Test fun keepsVerifiedRowsAndRetriesOnlyTheMissingReferences() = runTest {
        val source = fragments(1, 2)
        val answers = listOf(
            response(field(1, ref = null, unit = "mg/L"), field(2).replace("f2", "unknown")),
            response(field()),
            response(field()),
        ).iterator()
        val result = ExtractExam(runtime { GenerationResult.UnvalidatedFinal(answers.next(), 0, 0) })
            .extract(1..1, { source })
        val rows = result.pages.single().observations
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.reference != null && !it.referenceNeedsReview })
        assertFalse(result.partial)
        assertFalse(answers.hasNext())
    }

    @Test fun anInvalidRowDoesNotEraseAnIndependentVerifiedRow() {
        val block = ExamProtocol.blocks(ExamEvidence.prepare(1, fragments(1, 2)).rows).single()!!
        val malformed = field().replace("n1", "")
        val checked = ExamProtocol.validate(block, response(malformed, field(2)))
        assertEquals(listOf(block.rows.last().name.id), checked.observations.map { it.name.id })
        assertEquals(ExamIssue.EVIDENCE_NEEDS_REVIEW, checked.issue)
    }

    @Test fun verifiesPrintedCalculatedLabelsAndKeepsIdenticalOccurrencesDistinct() = runTest {
        val fragments = fragments(3, 6)
        val evidence = ExamEvidence.prepare(3, fragments)
        val blocks = ExamProtocol.blocks(evidence.rows)
        assertEquals(listOf(5, 1), blocks.map { it!!.rows.size })
        assertEquals(evidence.rows.map { it.result.id }, blocks.filterNotNull().flatMap { it.rows }.map { it.result.id })
        val last = blocks.last()!!.rows.single()
        assertEquals(3, last.result.regions.single().page)
        assertEquals(97.0, last.result.regions.single().top)
        val responses = blocks.filterNotNull().map { block -> response(*block.rows.indices.reversed().map { field(it + 1) }.toTypedArray()) }.iterator()
        val result = ExtractExam(runtime { GenerationResult.UnvalidatedFinal(responses.next(), 0, 0) }).extract(3..3, { fragments })
        assertEquals(evidence.rows.map { it.name.id }, result.pages.single().observations.map { it.name.id })
        assertTrue(result.pages.single().observations.all { it.name.text == "X calculado" })
        assertFalse(result.partial)
    }

    @Test fun overlappingCellsAreNotCitedAndUnsupportedPageDoesNotInvokeModel() = runTest {
        val overlap = fragments(1).map { fragment ->
            if (fragment.spanId == "v1") fragment.copy(region = region(1, 128.0, 37.0, 210.0)) else fragment
        }
        val result = ExtractExam(runtime { error("Unexpected inference") }).extract(1..2, { page ->
            if (page == 1) overlap else emptyList()
        })
        assertTrue(result.partial)
        assertTrue(result.pages.all { it.observations.isEmpty() && ExamIssue.INSUFFICIENT_POSITIONED_TEXT in it.issues })
    }

    @Test fun referenceBelowPreservesQualifierAndLeavesUnattachedContinuationUncertain() {
        val source = fragments(1).filterNot { it.spanId == "f1" }
        val rows = source + listOf(
            word(1, "f1", "Vide abaixo", 393.0, 37.0, 470.0),
            word(1, "ref-heading", "Intervalo de referência", 70.0, 107.0, 215.0),
            word(1, "condition", "Adultos:", 295.0, 115.0, 343.0),
            word(1, "bound", "< 8", 348.0, 115.0, 370.0),
        )
        val block = ExamProtocol.blocks(ExamEvidence.prepare(1, rows).rows).single()!!
        val checked = ExamProtocol.validate(block, response(field()))
        assertEquals("Intervalo de referência\nAdultos: < 8", checked.observations.single().reference?.text)
        val withoutHeading = rows.filterNot { it.spanId == "ref-heading" }
        val uncertain = ExamProtocol.blocks(ExamEvidence.prepare(1, withoutHeading).rows).single()!!
        assertEquals(null, uncertain.rows.single().reference)
        assertTrue(uncertain.rows.single().referenceUncertain)
    }

    @Test fun oversizedAndFailedBlocksDoNotEraseVerifiedRowsOrStopLaterPages() = runTest {
        val input = fragments(1, 7).map { fragment ->
            if (fragment.spanId == "f1") fragment.copy(raw = "ç".repeat(1000)) else fragment
        }
        val expected = ExamEvidence.prepare(1, input).rows
        val blocks = ExamProtocol.blocks(expected)
        assertEquals(listOf(null, 5, 1), blocks.map { it?.rows?.size })
        val final = response(field())
        var calls = 0
        val progress = mutableListOf<Pair<Int, Int>>()
        val finishedPages = mutableListOf<Pair<Int, Int>>()
        val result = ExtractExam(runtime {
            calls++
            if (calls == 1) GenerationResult.Failed(InferenceFailure.GENERATION_TIMEOUT)
            else GenerationResult.UnvalidatedFinal(final, 0, 0)
        }).extract(
            1..2,
            read = { if (it == 1) input else throw IOException() },
            onProgress = { page, block, _ -> progress += page to block },
            onPageFinished = { outcome -> finishedPages += outcome.page to outcome.observations.size },
        )
        assertEquals(listOf(1 to 0, 1 to 1, 1 to 2, 1 to 3, 2 to 0), progress)
        assertEquals(listOf(1 to 1, 2 to 0), finishedPages)
        assertEquals(listOf(expected.last().result.id), result.pages.first().observations.map { it.sourceResult.id })
        assertEquals(setOf(ExamIssue.INPUT_LIMIT, ExamIssue.INFERENCE_FAILED), result.pages.first().issues)
        assertEquals(setOf(ExamIssue.PAGE_UNREADABLE), result.pages.last().issues)
        assertTrue(result.partial)
    }

    @Test fun preventsExtraNativeRequestsOnADeviceWithoutModelSupport() = runTest {
        var calls = 0
        val result = ExtractExam(runtime {
            calls++
            GenerationResult.Failed(InferenceFailure.UNSUPPORTED_DEVICE)
        }).extract(1..3, { page -> fragments(page, 7) })
        assertEquals(1, calls)
        assertTrue(result.pages.all { it.inferenceFailures == setOf(InferenceFailure.UNSUPPORTED_DEVICE) })
    }

    @Test fun scientificUnitIsValidatedAsPrinted() {
        val source = fragments(1).map { fragment ->
            if (fragment.spanId == "v1") fragment.copy(raw = "7,5 10^6/µ L") else fragment
        }
        val block = ExamProtocol.blocks(ExamEvidence.prepare(1, source).rows).single()!!
        val verified = ExamProtocol.validate(block, response(field(result = "7,5")))
        assertEquals("10^6/µ L", verified.observations.single().unit)
        val changed = ExamProtocol.validate(block, response(field(result = "7,5", unit = "u2")))
        assertTrue(changed.observations.isEmpty())
    }

    @Test fun aLateNativeResponseCannotPublishOrStartAnotherPageAfterCancellation() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val read = mutableListOf<Int>()
        var finished = false
        val job = launch {
            ExtractExam(runtime {
                waiting.complete(Unit)
                withContext(NonCancellable) { release.await() }
                GenerationResult.UnvalidatedFinal(response(), 0, 0)
            }).extract(1..2, { page -> read += page; fragments(page) })
            finished = true
        }
        waiting.await()
        job.cancel()
        release.complete(Unit)
        job.cancelAndJoin()
        assertFalse(finished)
        assertEquals(listOf(1), read)
    }

    @Test fun pairedValuesRequireTheirOwnPrintedColumnUnitAndReference() {
        val header = fragments(1).take(3)
        val source = header + listOf(
            word(1, "p", "%", 216.0, 24.0, 222.0),
            word(1, "a", "/µL", 255.0, 24.0, 276.0),
            word(1, "name", "Item X", 20.0, 37.0, 90.0),
            word(1, "relative", "7,5", 204.0, 37.0, 222.0),
            word(1, "absolute", "25", 260.0, 37.0, 276.0),
            word(1, "relativeBound", "< 8", 370.0, 37.0, 400.0),
            word(1, "relativeUnit", "%", 404.0, 37.0, 408.0),
            word(1, "absoluteBound", "< 30", 420.0, 37.0, 452.0),
            word(1, "absoluteUnit", "/µL", 460.0, 37.0, 480.0),
        )
        val rows = ExamEvidence.prepare(1, source).rows
        assertEquals(listOf("7,5", "25"), rows.map { it.result.text })
        val block = ExamProtocol.blocks(rows).single()!!
        val valid = response(field(1, result = "7,5"), field(2, result = "25"))
        val checked = ExamProtocol.validate(block, valid)
        assertEquals(listOf("%", "/µL"), checked.observations.map { it.unit })
        assertEquals(listOf("< 8 %", "< 30 /µL"), checked.observations.map { it.reference?.text })
        assertEquals(listOf(24.0, 24.0), checked.observations.map { it.unitCitation!!.regions.single().top })
        val swapped = ExamProtocol.validate(block, response(
            field(1, value = "v2", result = "25", unit = "u2"),
            field(2, result = "25", unit = "u1"),
        ))
        assertTrue(swapped.observations.isEmpty())
    }

    @Test fun nameContinuationIsIncludedButMetadataAndAdjacentRowsRemainSeparate() {
        val evidence = ExamEvidence.prepare(1, fragments(1, 3).map { frag ->
            if (frag.region!!.top >= 49.0) frag.copy(region = frag.region.copy(top = frag.region.top + 24, bottom = frag.region.bottom + 24))
            else frag
        } + listOf(
            word(1, "continuation", "(estimado)", 20.0, 46.0, 88.0),
            word(1, "metadata", "Material X", 20.0, 55.0, 90.0),
        ))
        val first = evidence.rows.first()
        assertEquals("X calculado\n(estimado)", first.name.text)
        assertEquals(listOf(37.0, 46.0), first.name.regions.map { it.top })
        assertEquals("X calculado", evidence.rows.last().name.text)
        assertEquals(3, evidence.rows.size)
    }

    private fun runtime(answer: suspend (GenerationRequest) -> GenerationResult) = object : TextInference {
        override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult {
            assertEquals(ExtractionMode.ASSISTED, mode)
            assertEquals(ExamProtocol.SCHEMA_VERSION, request.schemaVersion)
            return answer(request)
        }
    }

    private fun fragments(page: Int, count: Int = 1): List<SourceFragment> =
        listOf(
            word(page, "hn", "Exame", 20.0, 12.0, 60.0),
            word(page, "hv", "Resultado", 175.0, 12.0, 230.0),
            word(page, "hr", "Referência", 425.0, 12.0, 475.0),
        ) + (1..count).flatMap { i ->
            val y = 25.0 + i * 12
            listOf(
                word(page, "n$i", "X calculado", 20.0, y, 135.0),
                word(page, "v$i", "< 7,5 mg/L", 207.0, y, 278.0),
                word(page, "f$i", "adultos: < 8", 380.0, y, 447.0),
            )
        }

    private fun word(page: Int, id: String, text: String, x: Double, y: Double, right: Double) =
        SourceFragment(id, text, region(page, x, y, right), "CONTRACT", "1")

    private fun region(page: Int, left: Double, top: Double, right: Double) =
        SourceRegion(page, left, top, right, top + 7, 500.0, 800.0)

    private fun response(vararg rows: String) = """{"schema":"${ExamProtocol.SCHEMA_VERSION}","rows":[${rows.joinToString(",")}]}"""

    private fun field(number: Int = 1, value: String? = "v$number", ref: String? = "f$number",
        result: String = "< 7,5", unit: String? = "u$number"): String {
        fun quoted(text: String?) = text?.let { kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: "null"
        return """{"n":"n$number","v":${quoted(value)},"r":${quoted(result)},"u":${quoted(unit)},"f":${quoted(ref)}}"""
    }
}
