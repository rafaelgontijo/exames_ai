package br.com.exames.inferenceexperimental

import br.com.exames.domain.model.Observation

/** Not linked to :app. No LiteRT runtime or model is shipped in the MVP. */
interface ExperimentalInference {
    suspend fun suggest(documentId: String): List<Observation>
}
class DisabledInference : ExperimentalInference {
    override suspend fun suggest(documentId: String): List<Observation> = emptyList()
}
