package br.com.exames.domain.extraction

import br.com.exames.domain.inference.InferenceFailure

enum class ExamStatusFilter {
    ALL,
    NEEDS_REVIEW,
    WITHIN_RANGE,
    NOT_CLASSIFIABLE,
}

data class ExamSummaryCounts(
    val total: Int,
    val reviewCount: Int,
    val withinRangeCount: Int,
    val unclassifiableCount: Int,
    val hasPageIssues: Boolean,
)

data class AssessedExamPage(
    val page: Int,
    val observations: List<AssessedObservation>,
    val issues: Set<ExamIssue>,
    val inferenceFailures: Set<InferenceFailure>,
)

data class AssessedExam(
    val pages: List<AssessedExamPage>,
    val counts: ExamSummaryCounts,
    val partial: Boolean,
) {
    fun allObservations(): List<AssessedObservation> = pages.flatMap { it.observations }

    fun firstReviewObservation(): AssessedObservation? =
        allObservations().firstOrNull { it.status == DocumentaryStatus.NEEDS_REVIEW }
            ?: allObservations().firstOrNull()

    fun filter(statusFilter: ExamStatusFilter): List<AssessedExamPage> {
        if (statusFilter == ExamStatusFilter.ALL) return pages
        val target = when (statusFilter) {
            ExamStatusFilter.NEEDS_REVIEW -> DocumentaryStatus.NEEDS_REVIEW
            ExamStatusFilter.WITHIN_RANGE -> DocumentaryStatus.WITHIN_RANGE
            ExamStatusFilter.NOT_CLASSIFIABLE -> DocumentaryStatus.NOT_CLASSIFIABLE
            ExamStatusFilter.ALL -> error("Handled above")
        }
        return pages.mapNotNull { page ->
            val matching = page.observations.filter { it.status == target }
            val keepIssues = statusFilter == ExamStatusFilter.NEEDS_REVIEW && page.issues.isNotEmpty()
            if (matching.isEmpty() && !keepIssues) null
            else page.copy(observations = matching)
        }
    }
}

object ExamSummaryProjection {
    fun project(
        extraction: ExamExtraction,
        context: ClinicalContext = ClinicalContext(),
        assessor: ConservativeExamAssessor = ConservativeExamAssessor(),
    ): AssessedExam {
        var total = 0
        var review = 0
        var within = 0
        var unclassifiable = 0
        val assessedPages = extraction.pages.map { page ->
            val assessedRows = page.observations.map { row ->
                val assessed = assessor.assess(row, context)
                total++
                when (assessed.status) {
                    DocumentaryStatus.NEEDS_REVIEW -> review++
                    DocumentaryStatus.WITHIN_RANGE -> within++
                    DocumentaryStatus.NOT_CLASSIFIABLE -> unclassifiable++
                }
                assessed
            }
            AssessedExamPage(page.page, assessedRows, page.issues, page.inferenceFailures)
        }
        return AssessedExam(
            pages = assessedPages,
            counts = ExamSummaryCounts(
                total = total,
                reviewCount = review,
                withinRangeCount = within,
                unclassifiableCount = unclassifiable,
                hasPageIssues = extraction.partial,
            ),
            partial = extraction.partial,
        )
    }
}
