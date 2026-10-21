package br.com.exames.domain.usecase

import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.*

class EvaluateObservation(private val evaluator: DeterministicRangeEvaluator) {
    operator fun invoke(observation: Observation, reference: ReferenceRange?): Assessment =
        evaluator.evaluate(observation, reference)
}
