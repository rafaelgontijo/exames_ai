package br.com.exames.featurehistory

import org.junit.Assert.*
import org.junit.Test

class HelpContentTest {
    private val forbidden = listOf("urgên", "urgen", "emergên", "pronto atendimento", "pronto-socorro", "prazo", "semanas", "especialidade", "especialista",
        "cardiolog", "endocrinolog", "ubs", "perto de mim", "consulta", "médico", "medico", "doença", "doenca", "diabetes", "colesterol",
        "tudo normal", "está bom", "sintoma", "tratamento", "ldl", "hemoglobina")
    @Test fun helpHasNoTriageUrgencyTimingSpecialtiesDiseasesOrPlacesOfCare() {
        val text = HelpContent.allText().lowercase()
        for (bad in forbidden) assertFalse("HELP_CONTAINS $bad", Regex("\\b${Regex.escape(bad)}").containsMatchIn(text))
    }
    @Test fun helpCoversTheRequiredTopicsAndOnlyTheFiveSupportedAnalytes() {
        val titles = HelpContent.sections.map { it.title }
        for (topic in listOf("Como ler o resultado", "Quando o app pede revisão", "Conferir na fonte", "Leitura neste aparelho", "Ouvir e compartilhar são voluntários"))
            assertTrue(topic, topic in titles)
        val text = HelpContent.allText().lowercase()
        for (analyte in listOf("glicose", "ureia", "creatinina", "sódio", "potássio")) assertTrue(analyte, analyte in text)
        assertTrue("não é um diagnóstico" in text && "não interpreta resultados" in text)
    }
    @Test fun helpStatesLocalProcessingAndSharingLimits() {
        val text = HelpContent.allText()
        assertTrue(text.contains("neste aparelho, sem internet"))
        assertTrue(text.contains("o app não consegue apagá-la"))
    }
}
