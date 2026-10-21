package br.com.exames.app.di

import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.usecase.EvaluateObservation
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {
    @Provides fun evaluator(): DeterministicRangeEvaluator = DeterministicRangeEvaluator()
    @Provides fun evaluate(evaluator: DeterministicRangeEvaluator): EvaluateObservation = EvaluateObservation(evaluator)
}
