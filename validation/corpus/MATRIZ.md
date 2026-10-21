# Matriz sintética v1

Todos os cenários em `cases.json` têm respostas esperadas explícitas anteriores à renderização, sem chamar parser, avaliador ou modelo. Cada cenário contém os cinco analitos e será renderizado nos layouts `simple-v1` e `footnote-v1`. Os valores/faixas são arbitrários, sem significado clínico.

| Cenário | Estado esperado | Risco exercitado |
| --- | --- | --- |
| within | WITHIN_RANGE | Escalar legível |
| below / above | BELOW_RANGE / ABOVE_RANGE | Direção da comparação |
| inclusive-low / inclusive-high | WITHIN_RANGE | Igualdade em limites inclusivos |
| exclusive-high | ABOVE_RANGE | Igualdade em limite exclusivo |
| upper-only / lower-only | ABOVE_RANGE / BELOW_RANGE | Referência unilateral |
| censored | NEEDS_REVIEW | Resultado censurado |
| ambiguous-comma / ambiguous-dot | NEEDS_REVIEW | Decimal versus milhares |
| unit-mismatch | NEEDS_REVIEW | Unidade de resultado e referência diferente |
| missing-reference | NOT_CLASSIFIABLE | Ausência de faixa |
| unknown-context | NEEDS_REVIEW | Faixa condicionada, contexto ausente |
| qualitative | NOT_CLASSIFIABLE | Resultado qualitativo |
| invalid-reference | NEEDS_REVIEW | Limites invertidos |
| competing-reference | NEEDS_REVIEW | Associação não resolvida |
| unreadable | NEEDS_REVIEW | Resultado propositalmente oculto |

Variantes: PDF textual, PDF rasterizado, PNG, JPEG, PNG rotacionado 90° e JPEG degradado. Todas compartilham `familyId` por cenário, inclusive entre layouts; a família inteira fica em desenvolvimento ou avaliação reservada. A rotação/degradação fica registrada; somente `unreadable` garante ausência visual, sem supor que borrão leve impede leitura. No PDF textual desse cenário o valor não é incluído na camada textual.

Gabaritos gerados preservam texto bruto, decimais canônicos como strings, faixa, motivo esperado, recuperabilidade, página e regiões na coordenada do documento final. Não há pacientes nem dados pessoais.
