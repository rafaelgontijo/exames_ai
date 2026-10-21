# Spec Delta

## Purpose

Comparar resultados com referências do documento de forma automática e conservadora, sem confundir sinalização referencial com avaliação clínica.

## ADDED Requirements

### Requirement: Automatic assessment does not fabricate human confirmation
O sistema SHALL avaliar observações inequívocas e completas sem confirmação prévia, identificando execução automática, regra/versão, horário e motivo. Ausência de confirmação SHALL permanecer distinguível de revisão humana real.

#### Scenario: Complete unreviewed observation
- **WHEN** valor, unidade e referência são inequívocos, compatíveis e aplicáveis, mas não revisados
- **THEN** a classificação é calculada sem marcar confirmação por pessoa

### Requirement: Ambiguity requires abstention
Ambiguidade de leitura, número, unidade, comparador, associação ou contexto SHALL resultar em NEEDS_REVIEW com motivo. Confiança de OCR e confirmação genérica SHALL NOT eliminar bloqueios. Resultados censurados SHALL exigir revisão nesta versão.

#### Scenario: Censored or ambiguous number
- **WHEN** o resultado é `1,234`, `1.234` ou `< 0,1`
- **THEN** preserva o texto e retorna NEEDS_REVIEW sem supor valor escalar

#### Scenario: Unknown conditional context
- **WHEN** a referência depende de idade, sexo, gestação, jejum ou método não resolvido
- **THEN** retorna NEEDS_REVIEW inclusive após confirmação genérica

### Requirement: Assessment uses compatible document references only
O sistema SHALL usar referências explicitamente associadas ao resultado e aplicáveis. SHALL exigir unidades explícitas iguais após remoção de espaços externos, sem conversão. Referências inválidas ou concorrentes SHALL exigir revisão.

#### Scenario: Different units
- **WHEN** resultado e referência usam unidades diferentes, mesmo com conversão conhecida
- **THEN** retorna NEEDS_REVIEW sem conversão implícita

### Requirement: Numeric comparison respects exact bounds
Para escalares válidos, o sistema SHALL comparar decimais sem arredondamento destrutivo, respeitando limites inclusivos/exclusivos e unilaterais. SHALL retornar BELOW_RANGE, WITHIN_RANGE ou ABOVE_RANGE sem atribuir gravidade ou urgência.

#### Scenario: Inclusive and exclusive upper boundaries
- **WHEN** o resultado iguala o limite superior
- **THEN** retorna WITHIN_RANGE para limite inclusivo e ABOVE_RANGE para exclusivo

### Requirement: Unsupported content is distinguishable from unresolved content
Conteúdo legível fora das regras, como resultado qualitativo ou referência ausente, SHALL resultar em NOT_CLASSIFIABLE quando não houver ambiguidade anterior. O sistema SHALL NOT apresentar ausência de classificação como WITHIN_RANGE.

#### Scenario: Missing reference
- **WHEN** o resultado é inequívoco mas não há referência no documento
- **THEN** retorna NOT_CLASSIFIABLE com motivo explícito sem buscar faixa externa

### Requirement: Assessment is independent of expected answers
O processamento automático SHALL usar somente o documento e regras versionadas. Gabaritos, correções posteriores e classificações esperadas SHALL NOT alimentar a execução original.

#### Scenario: Expected answer is changed
- **WHEN** muda apenas o gabarito
- **THEN** a saída original permanece a mesma, embora sua comparação com o gabarito possa mudar

### Requirement: Model-assisted fields use the same deterministic assessment
Campos propostos SHALL passar por validação independente de evidência, associação e contexto antes do mesmo avaliador usado sem IA. A classificação final SHALL NOT ser gerada pelo modelo; falta de suporte, conflito e ambiguidade SHALL preservar NEEDS_REVIEW.

#### Scenario: Model proposes a confident classification
- **WHEN** o modelo sugere WITHIN_RANGE com unidade incompatível ou faixa sem evidência
- **THEN** a sugestão é ignorada e o sistema exige revisão pelas mesmas regras do modo sem IA
