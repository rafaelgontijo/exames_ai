# Avaliação referencial v2

`REF_NUMERIC/2.0.0` substitui a exigência de confirmação prévia da regra 1.0.0. `Observation.confirmed` continua a indicar revisão humana: o avaliador não o altera. Uma observação inequívoca e completa pode ser comparada automaticamente, mas confirmação genérica nunca elimina ambiguidade ou contexto desconhecido.

A ordem das barreiras é: ambiguidade → referência ausente → associação de referência → aplicabilidade → comparador de referência → censura do resultado → unidade → valor escalar e sua concordância com texto bruto → validade da faixa → comparação. Ambiguidade anterior tem precedência sobre ausência de referência. O código `UNCONFIRMED` permanece no enum para leitura de dados legados; a regra v2 não o produz.

Valores e limites são `BigDecimal`; não converter para Float/Double nem arredondar para decidir igualdade. Unidades devem ser explícitas e iguais após remover espaços externos; não normalizar caixa ou converter unidades implicitamente. Referência e resultado pertencem à mesma observação e a aplicabilidade precisa ser explicitamente resolvida. Resultados censurados e números ambíguos como `1,234`/`1.234` exigem revisão.

| Exemplo sintético | Referência sintética | Estado |
| --- | --- | --- |
| 9,99 u/L | [10,20] u/L | BELOW_RANGE |
| 10 u/L | [10,20] u/L | WITHIN_RANGE |
| 20 u/L | [10,20] u/L | WITHIN_RANGE |
| 20,01 u/L | [10,20] u/L | ABOVE_RANGE |
| 20 u/L | [10,20) u/L | ABOVE_RANGE |
| 20 u/L | <20 u/L | ABOVE_RANGE |
| 20 u/L | ≤20 u/L | WITHIN_RANGE |
| <15 u/L | [10,20] u/L | NEEDS_REVIEW |
| 15 mg/dL | [10,20] u/L | NEEDS_REVIEW |
| 15 u/L, sem faixa | ausente | NOT_CLASSIFIABLE |

Esses exemplos são dados de testes, não referências clínicas. São exercitados em `DeterministicRangeEvaluatorTest` e `ConservativeNumberParserTest`. `NEEDS_REVIEW` identifica dúvida de extração, associação ou contexto; `NOT_CLASSIFIABLE` indica conteúdo legível fora das regras quando nenhuma barreira anterior exige revisão. Nenhum desses estados significa resultado dentro da faixa. A classificação não define diagnóstico, gravidade ou urgência.

Os layouts `simple-v1` e `footnote-v1` já têm fixtures e matriz em `validation/corpus/`, incluindo texto, raster e rotação. O parser de layouts Android ainda é uma tarefa pendente; este documento não declara reconhecimento já implementado. Ampliar layouts exige corpus, evidência e testes de associação, sem afrouxar essas regras.

Campos de qualquer futuro extrator assistido passam pelas mesmas barreiras. O avaliador não recebe gabarito ou saída clínica do modelo. Execuções antigas mantêm sua versão registrada; a migração futura deve preservar 1.0.0 sem reclassificar silenciosamente dados legados.
