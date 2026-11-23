# Spec Delta

## Purpose

Avalia de forma determinística e conservadora cada medição extraída do PDF contra a referência bruta vinculada no próprio documento, classificando-a em `Na referência`, `Revisar` ou `Sem faixa` sem nunca inventar intervalos nem afrouxar o fallback de revisão.

## ADDED Requirements

### Requirement: Classify numeric measurements conservatively against a single explicit reference range

O domínio SHALL avaliar cada medição extraída apenas quando houver valor numérico simples sem desigualdade, faixa de referência única e explícita no texto bruto vinculado, ausência de incerteza estrutural (`referenceNeedsReview` falso) e unidade explícita compatível, classificando como `Na referência` quando o valor estiver dentro dos limites da faixa ou `Revisar` quando estiver abaixo ou acima dos limites informados.

#### Scenario: Value inside a single explicit numeric range with matching unit
- **WHEN** uma medição extraída possui valor numérico exato (ex.: `14,2 g/dL`), vínculo confirmado e referência única com a mesma unidade (ex.: `13,5 a 17,5 g/dL`)
- **THEN** o avaliador classifica o item como `Na referência` com motivo documental indicando que o valor está dentro da faixa informada no documento

#### Scenario: Value above or below a single explicit numeric range
- **WHEN** uma medição extraída possui valor numérico exato fora dos limites da faixa única explícita com mesma unidade (ex.: `118 mg/dL` para `70 a 99 mg/dL` ou `162 mg/dL` para `< 130 mg/dL`)
- **THEN** o avaliador classifica o item como `Revisar` indicando se está acima ou abaixo da faixa informada no documento, preservando o texto bruto original

### Requirement: Enforce strict NEEDS_REVIEW fallback for ambiguous, multi-criteria, or incompatible evidence

O domínio SHALL obrigatoriamente classificar como `Revisar` (`NEEDS_REVIEW`) qualquer medição cujo vínculo de referência esteja sinalizado como incerto, cuja referência contenha múltiplos critérios ou condições de contexto desconhecidas (ex.: sexo, idade, jejum ou estratificação de risco), cuja unidade seja ausente ou incompatível com a referência, ou cujo resultado seja censurado/desigualdade (ex.: `< 0,5`, `> 100`) ou de interpretação numérica ambígua.

#### Scenario: Multi-criteria or conditional reference text
- **WHEN** o texto de referência vinculado contém múltiplas faixas ou critérios condicionais (ex.: faixas por risco cardiovascular, sexo ou faixa etária)
- **THEN** o avaliador não escolhe uma faixa e classifica obrigatoriamente o item como `Revisar` com motivo de referência ambígua ou condicional

#### Scenario: Missing or incompatible unit between value and reference
- **WHEN** a unidade da medição difere da unidade da referência ou está ausente em uma das partes onde a comparação exige compatibilidade explícita
- **THEN** o avaliador não converte nem presume unidades e classifica obrigatoriamente o item como `Revisar` com motivo de unidade incompatível ou ausente

#### Scenario: Censored or inequality measurement result
- **WHEN** o resultado impresso contém operador de desigualdade (ex.: `< 5,0` ou `>= 60`) frente a uma faixa de referência
- **THEN** o avaliador classifica obrigatoriamente o item como `Revisar` sem assumir posição dentro da faixa

### Requirement: Classify missing reference or non-numeric results as unclassifiable without inventing ranges

O domínio SHALL classificar como `Sem faixa` (`NOT_CLASSIFIABLE`) medições sem texto de referência vinculado (quando o vínculo não está marcado como incerto) e medições com resultados qualitativos ou não numéricos sem regra numérica aplicável, nunca inventando valores de referência externos ao documento.

#### Scenario: Measurement without reference text in the report
- **WHEN** uma medição verificada não possui referência vinculada e não há indicação de vínculo incerto
- **THEN** o avaliador classifica o item como `Sem faixa` com motivo de ausência de referência comparável no documento

#### Scenario: Qualitative or non-numeric measurement
- **WHEN** uma medição apresenta resultado qualitativo ou textual (ex.: `Reagente`, `Ausente`, `Negativo`)
- **THEN** o avaliador preserva o texto bruto e classifica o item como `Sem faixa` (ou `Revisar` se o vínculo de referência for incerto) sem emitir diagnóstico

### Requirement: Aggregate counts and filter projections deterministically without mutating raw evidence

O domínio SHALL produzir projeções determinísticas de contagem total por estado (`Revisar`, `Na referência`, `Sem faixa`) e filtragem em ordem documental para a interface e para o resumo do histórico, preservando integralmente os textos brutos e regiões de evidência da extração original.

#### Scenario: Compute exam summary counts for history and result filters
- **WHEN** uma extração contém medições nos estados `Revisar`, `Na referência` e `Sem faixa`
- **THEN** as contagens agregadas do resumo coincidem exatamente com a soma dos itens filtráveis em cada aba de filtro e todos os textos brutos originais permanecem inalterados
