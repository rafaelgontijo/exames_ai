package br.com.exames.app

/**
 * Navigation routes for the unified Meu Exame app.
 * Routes carry only fixed names or opaque exam IDs; never clinical text, values, or URIs.
 */
internal object AppRoutes {
    const val HOME = "home"
    const val EXAMS = "exams"
    const val HELP = "help"
    const val HELP_REVIEW = "help/review"
    const val SEND = "send"
    const val PROCESSING = "processing/{examId}"
    const val RESULTS = "results/{examId}"
    const val EVIDENCE = "evidence/{examId}"

    val bottomTabRoutes = setOf(HOME, EXAMS, HELP)
    private val opaqueIdPattern = Regex("[a-zA-Z0-9_-]{1,80}")

    fun isOpaqueId(id: String): Boolean = opaqueIdPattern.matches(id)

    fun processing(examId: String): String {
        require(isOpaqueId(examId)) { "Invalid opaque examId" }
        return "processing/$examId"
    }

    fun results(examId: String): String {
        require(isOpaqueId(examId)) { "Invalid opaque examId" }
        return "results/$examId"
    }

    fun evidence(examId: String): String {
        require(isOpaqueId(examId)) { "Invalid opaque examId" }
        return "evidence/$examId"
    }
}
