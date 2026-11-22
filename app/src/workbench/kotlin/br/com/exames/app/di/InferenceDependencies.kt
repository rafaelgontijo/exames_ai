package br.com.exames.app.di

import br.com.exames.domain.inference.TextInference
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface InferenceDependencies {
    fun inference(): TextInference
}
