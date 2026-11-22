package br.com.exames.featurehistory

/** Review help text. Documentary guidance only: no triage, urgency, timing, specialties, diseases or places of care. */
object HelpContent {
    data class Section(val title: String, val body: String)
    val sections: List<Section> = listOf(
        Section("Como ler o resultado",
            "O app compara cada valor com a referência impressa no próprio documento e mostra se ele está abaixo, dentro ou acima dessa referência informada. " +
                "Isso é uma comparação de números e não é um diagnóstico."),
        Section("Quando o app pede revisão",
            "Se a unidade, o resultado ou a referência estiverem ausentes, ambíguos ou incompatíveis, o campo aparece como “Precisa de revisão” ou “Não classificável”. " +
                "O app não adivinha e não completa dados que o documento não traz."),
        Section("Conferir na fonte",
            "Em cada campo, abra a página da cópia privada e destaque o trecho lido, comparando com o papel ou PDF original. " +
                "Se algo foi lido errado, registre uma correção: a leitura original continua preservada ao lado dela."),
        Section("Leitura neste aparelho",
            "O processamento acontece neste aparelho, sem internet. Os arquivos ficam privados e protegidos dentro do app. " +
                "Excluir um documento remove a cópia privada e tudo o que foi derivado dela."),
        Section("Ouvir e compartilhar são voluntários",
            "O app só lê o resumo em voz alta ou o compartilha quando você escolhe. Antes de compartilhar, você vê exatamente o texto. " +
                "Quem receber poderá guardar ou repassar a cópia, e o app não consegue apagá-la."),
        Section("Limites desta versão",
            "O app lê cinco analitos: glicose, ureia, creatinina, sódio e potássio. Outros itens do documento são sinalizados como não suportados. " +
                "O app não interpreta resultados nem recomenda condutas."),
    )
    const val DETAILS_TITLE = "Detalhes da bancada"
    const val DETAILS_BODY = "Acesse a fonte destacada, correções, gabaritos, versões, relatórios, reexecução, cancelamento e exclusão pela lista de exames."
    const val DETAILS_ACTION = "Abrir lista de exames"

    fun allText(): String = (sections.flatMap { listOf(it.title, it.body) } + listOf(DETAILS_TITLE, DETAILS_BODY, DETAILS_ACTION)).joinToString("\n")
}
