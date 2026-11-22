package br.com.exames.featureresults

import br.com.exames.domain.validation.PageCheckpoint
import br.com.exames.domain.validation.ProcessingRun

internal fun inferenceParticipation(run: ProcessingRun, pages: List<PageCheckpoint>) =
    br.com.exames.domain.summary.inferenceParticipation(run, pages)
