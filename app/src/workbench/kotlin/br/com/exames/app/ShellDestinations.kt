package br.com.exames.app

/** Route table of the shell. Routes carry only fixed names or opaque run IDs, never clinical text or URIs. */
object ShellRoutes {
    const val HOME = "home"
    const val EXAMS = "exams"
    const val HELP = "help"
    const val SEND = "send"
    val topLevel: List<String> = listOf(HOME, EXAMS, HELP)
    val fixed: Set<String> = setOf(HOME, EXAMS, HELP, SEND)

    /** Only the three bottom destinations show navigation; Enviar and Resultado are document flow screens. */
    fun showsBottomBar(route: String?): Boolean = route in topLevel
    fun isResults(route: String?): Boolean = route == ResultRoutes.PATTERN
}
