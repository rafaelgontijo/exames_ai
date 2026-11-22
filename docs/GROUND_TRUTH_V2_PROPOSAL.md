# Proposta: entradas canônicas para o ensaio isolado do avaliador

Estado: aprovada pelo usuário e implementada em 27/10/2026 para a tarefa 6.3.
A versão 1 permanece intacta; a versão 2 usa schema 1.1 e entradas canônicas explícitas.

## Problema concreto

`ExpectedObservation` declara texto, numericValue, low/high, estado/motivo esperado
e evidências. Não declara comparador do resultado, comparador/inclusividade da
referência, unidade própria da referência, aplicabilidade ou ambiguidades de entrada.
Esses campos são entradas reais de `DeterministicRangeEvaluator`.

Exemplo: para resultado 20 e referência `10 ≤ x < 20`, usar os defaults inclusivos
do contrato produziria WITHIN_RANGE por erro do harness. Reusar o parser de
produção para preencher essas entradas misturaria avaliação isolada e extração.
Derivá-las de expectedStatus/expectedReason faria a resposta esperada influenciar
o ensaio. Os dois atalhos distorcem a medição.

## Mudança proposta

Adicionar `evaluationInput` opcional em `ExpectedObservation`, contendo snapshot
de entrada completo e declarado independentemente do motor:

- Comparador do resultado e ambiguidades da observação.
- Presença de referência selecionável; ausência e seleção não resolvida são distintas.
- Unidade própria da referência, comparador, inclusividade e aplicabilidade.
- Vínculo/condição e candidatos explícitos quando a referência não for inequívoca.

Valores numéricos e textos já declarados continuam sendo a fonte única desses
dados; o novo bloco completa metadados, sem inventar faixas ou duplicar valores
com defaults clínicos. O harness constrói entradas exclusivamente desse snapshot.
Não chama parser/modelo para completar gabarito nem lê respostas esperadas para
decidir comparadores, contexto ou ambiguidades.

| Cenário | Entrada explícita adicional | Estado esperado preservado |
| --- | --- | --- |
| inclusive-high | RANGE; low/high inclusivos | WITHIN_RANGE |
| exclusive-high | RANGE; low inclusivo, high exclusivo | ABOVE_RANGE |
| upper-only | LT com high presente | ABOVE_RANGE no limite |
| lower-only | GT com low presente | BELOW_RANGE no limite |
| censored | resultado LT; sem valor escalar inventado | NEEDS_REVIEW |
| unit-mismatch | unidade da referência explicitamente diferente | NEEDS_REVIEW |
| missing-reference | referência ausente | NOT_CLASSIFIABLE |
| unknown-context | contexto/seleção não resolvidos; nenhuma faixa escolhida | NEEDS_REVIEW |
| competing-reference | candidatos preservados; associação não resolvida | NEEDS_REVIEW |

Os motivos normativos também precisam de revisão documental: v1 declara
REFERENCE_MISMATCH para faixas concorrentes e CONTEXT_UNRESOLVED para contexto
ausente. Entradas com ambiguidades explícitas têm precedência AMBIGUOUS_INPUT
em REF_NUMERIC/2.0.0. A proposta é declarar isso na v2 mantendo o estado
NEEDS_REVIEW e todos os motivos v1 intactos no histórico. Não alterar o motor
nem relaxar abstenção para fazer o gabarito passar.

## Versionamento e verificação propostos

1. Preservar `validation/corpus/cases.json`, schema 1.0 e gabaritos versão 1.
2. Acrescentar fonte declarativa `cases-v2.json`, contrato/schema 1.1 com bloco
   opcional e gabaritos versão 2; corpus/gerador passam a synthetic-2.0 / 2.0.
3. Gerar em diretório separado. Manter fonte, valores, fontes tipográficas,
   seeds, famílias, partições e documentos; conferir que documentos e seus hashes
   permanecem idênticos. Somente gabaritos/manifestos recebem novos hashes.
4. Leitura antiga continua possível; gabarito sem evaluationInput tem ensaio
   isolado N/A com motivo explícito. Ele continua utilizável no ensaio ponta a ponta.
5. Testar limites abertos/fechados, censura, unidade/contexto, concorrência de
   referências e compatibilidade. Modificar somente gabarito não pode alterar
   saídas/configurações de processamento existentes.

## Decisão e execução

O usuário aprovou prosseguir com a versão 2, preservando v1 e explicitando os
motivos novos. Fontes em `validation/corpus/cases-v2.json`, schemas 1.1 e
`tools/corpus/generate_v2.py`. Os 216 documentos foram confrontados em bytes/hash
com a v1 regenerada pelo renderer original neste host, sem mudança de famílias,
partições, valores ou estados esperados. Os arquivos fonte/schema v1 continuam
idênticos ao HEAD. O ZIP histórico cloud não estava disponível para confronto
entre hosts; esse limite está registrado no relatório técnico.

Os 90 inputs dos 18 cenários passaram em estados/motivos esperados, e a extração
de PDF privado real foi medida separadamente no S25. Compatibilidade v1, gabarito
pendente, entradas ausentes/inválidas e versão de regra não suportada dão N/A
explícito no ensaio isolado. O avaliador permaneceu inalterado. Ver
`validation/results/ground-truth-v2-2026-10-27.json` e `docs/VALIDATION_COMPARISON.md`.
