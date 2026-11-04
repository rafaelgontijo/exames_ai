# Resumo referencial

Contrato do resumo usado pela prévia, pelo áudio e pela exportação (`:domain`, pacote `summary`). É uma
projeção determinística de saídas já persistidas: não avalia, não converte unidades, não promove status e não usa
resposta ou raciocínio de modelo. Não é diagnóstico nem orientação clínica; abstenções e ambiguidades permanecem
`NEEDS_REVIEW`/`NOT_CLASSIFIABLE`.

## Projeção (`ProjectReferenceSummary`, `REFERENCE_SUMMARY/1.0.0`)

Entrada: execução explícita, observações, avaliações, checkpoints e número esperado de páginas.

| Situação | Resultado |
| --- | --- |
| Observação sem avaliação, ou com avaliações duplicadas | `NEEDS_REVIEW` sem motivo/ambíguo, aviso `ASSESSMENT_MISSING` |
| Avaliação sem observação | ignorada nas contagens, aviso `ORPHAN_ASSESSMENT` |
| Referência ausente | aviso `REFERENCE_MISSING`; nenhuma referência inventada |
| Ambiguidades | aviso `AMBIGUITIES_PRESENT`; conjunto preservado por linha |
| `parseWarnings` de páginas | aviso `UNSUPPORTED_CONTENT` e códigos preservados |
| Unidade incompatível, resultado censurado | status e motivo da avaliação existente, texto bruto preservado |
| Sem observações | aviso `NO_OBSERVATIONS`; contagens zero |

Completude: `COMPLETE` somente com execução `COMPLETE`, número de páginas conhecido e todas com checkpoint completo.
Execução `QUEUED`/`RUNNING` é `IN_PROGRESS`; `PARTIAL`, ou `FAILED`/`CANCELLED`/falta de página com ao menos uma página
completa, é `PARTIAL`; sem página completa, `UNAVAILABLE`. Qualquer completude diferente de `COMPLETE` acrescenta
`PAGES_INCOMPLETE`, e execução não `COMPLETE` acrescenta `RUN_NOT_COMPLETE`.

Contagens separadas: abaixo, dentro e acima da referência informada, requer revisão e não classificável. A soma é o
número de observações.

## Texto (`SummaryText`, `SUMMARY_TEXT/1.0.0`)

- Identifica saída automática/corrigida/reavaliada, modo (sem IA ou com IA local), prefixo da execução, estado, regra,
  contagens, campos, motivos, ambiguidades e limitações.
- Copia somente campos estruturados que casam formatos conservadores: analito normalizado entre os cinco suportados;
  resultado numérico com comparador opcional; unidade de uma lista fixa; referência numérica (faixa ou limite), todos
  com até 40 caracteres. Qualquer outro texto vira `[conferir na fonte privada]` e é contado em "campo(s) omitido(s)".
- Não inclui cabeçalhos, nomes, identificadores de paciente, caminhos, URIs, IDs de documento, raciocínio ou termos
  clínicos. Linguagem: "dentro da referência informada", nunca "tudo normal".
- Limite: 64 KiB em UTF-8 (`65536` bytes) para o texto completo. O limite exato é aceito; excedê-lo retorna
  `TooLarge(utf8Bytes, limitBytes)` sem texto parcial, de modo que prévia, fala e compartilhamento falham antes de agir.

## Exemplo sintético

O exemplo abaixo é verificado por `SummaryDocExamplesTest` contra a saída real (fixture sintética; a segunda linha
simula texto livre com marcador de identidade, omitido do resultado).

<!-- example:start -->
```text
Resumo referencial (saída automática, modo sem IA, execução 01234567).
Estado da execução: parcial, 1 página(s) completa(s).
Regra REF_NUMERIC/2.0.0, resumo SUMMARY_TEXT/1.0.0.
Contagens: abaixo da referência informada 0, dentro da referência informada 1, acima da referência informada 0, requer revisão 1, não classificável 0.
1. Glicose (página 1): resultado 90, unidade mg/dL, referência do documento 70-99. Situação: dentro da referência informada (motivo INSIDE_REFERENCE).
2. Ureia (página 1): resultado [conferir na fonte privada], unidade mg/dL, referência do documento 15-45. Situação: requer revisão (motivo AMBIGUOUS_INPUT); ambiguidades VALUE.
Limitações: AMBIGUITIES_PRESENT, PAGES_INCOMPLETE, RUN_NOT_COMPLETE.
1 campo(s) omitido(s) por texto livre ou não suportado; conferir na fonte privada.
Comparação com referências informadas no documento; não é diagnóstico nem orientação clínica.
```
<!-- example:end -->

## Início (`ProjectHome`)

Distingue `Unavailable` (leitura falhou), `Empty` (sem documentos visíveis) e `Loaded`. Documentos em exclusão ficam
ocultos. Cada cartão traz formato genérico, data de importação e, por modo, a última execução `AUTOMATIC` pelo horário
de início, com o estado dela mesma: uma execução antiga concluída não substitui uma mais nova em fila. Execuções
corrigidas/reavaliadas são apenas contadas e nunca substituem o resumo automático.
