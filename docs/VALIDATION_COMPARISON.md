# Comparação da extração com o gabarito

`ExtractionComparator` é Kotlin/JVM puro e compara um caso/documento por chamada.
Versão `case-page-analyte-evidence/1`. Não altera observações, avaliações ou
gabarito e não executa extração/inferência. A orquestração com repositórios,
relatórios e UI continua nas tarefas seguintes.

O pareamento usa analito reconhecido, página do analito e interseção das regiões
da evidência do analito, normalizadas pelas dimensões do viewport. Não lê valor
ou faixa para escolher um par. Sem geometria, exige correspondência mutuamente
única por analito/página; página desconhecida não é presumida. Regiões concorrentes
permanecem não resolvidas. Não escolhe pela menor distância nem força um matching
completo presumindo que todos os esperados foram extraídos.

O resultado separa pares um-para-um, ausentes, extras e candidatos não resolvidos.
IDs duplicados ou documentos misturados são rejeitados. Uma referência em outra
página não muda a página esperada do analito.

A comparação bruta mantém analito, resultado, unidade e referência completos.
`Observation.referenceRaw` conserva conectores entre candidatos; o campo opcional
fica no payload cifrado existente, com leitura de payloads antigos sem essa chave.
O schema SQL v2 não mudou. Dados legados podem usar a referência selecionada
quando não possuírem o campo bruto completo.

A comparação canônica separa analito, BigDecimal sem diferença de escala,
unidade com remoção apenas de espaços externos, limites, vínculo da referência
à observação e estado da avaliação. Usa os decimais explicitamente armazenados
no gabarito, sem gerar respostas esperadas pelo avaliador sob teste. Não mede
acerto canônico de metadados que o contrato atual do gabarito não declara, como
um campo separado de comparador. Não converte unidade. Trocar valores esperados
não altera o pareamento; faixas trocadas permanecem erros nas observações originais.

Testes cobrem ausentes/extras, duplicatas, escala de raster, páginas desconhecidas,
rodapés, falta de geometria, troca de referências, identidade inválida e distinção
bruta/canônica. Instrumentados verificam reabertura cifrada do novo campo e ausência
de seu marcador sintético em claro. Trata-se de comparação técnica de fixtures;
não há conclusão clínica.

## Contagens e denominadores

`ValidationMetricsCalculator` conserva numerador/denominador inteiros; divisão
por zero produz ratio null (N/A). Apenas gabarito REVIEWED com autor e data válida
entra nos denominadores. Pendentes/inválidos têm quantidade explícita, sem acerto.

Acerto bruto usa quatro campos por esperado; canônico usa sete propriedades
declaradas acima. Ausentes contam como incorretos, inclusive no resultado por
campo quando nenhuma observação foi pareada. Extras são contados separadamente.
Cobertura numérica conta somente esperados pareados com estado numérico observado,
divididos por todos os esperados revisados. Acurácia de classificação também usa
todos os esperados revisados. Não reexecuta o avaliador para corrigir sua saída.

O relatório de anormais separa direção correta, direção errada, exibido como
WITHIN_RANGE, NEEDS_REVIEW, NOT_CLASSIFIABLE, observação ausente e avaliação ausente.
Anormal não sinalizado soma as cinco últimas categorias, com denominador de
anormais esperados. Direção errada continua explícita como erro próprio.
Estados de extras não aumentam cobertura. Execução parcial permanece identificada.

Teste com oito esperados e dois extras verifica cobertura 4/8, classificação
correta 2/8, campos brutos 28/32, fora→dentro 1/7 e anormais não sinalizados 5/7.
Outros testes cobrem vazio/N/A, cobertura zero, abstenção total, saída inteiramente
ausente, duplicatas e metadados de revisão inválidos. A UI/relatório persistido
e orquestração dos dois ensaios continuam nas tarefas seguintes.

## Medições isolada e ponta a ponta (6.3)

`IsolatedEvaluatorMeasurement` usa somente `evaluationInput` e textos/valores
declarados no gabarito v2. Resultado esperado é lido após executar o avaliador.
Comparadores, unidades, inclusividade, vínculo, contexto e ambiguidades não são
preenchidos pelo parser nem derivados da resposta esperada. Candidatos concorrentes
permanecem não selecionados. Página do analito vem da evidência declarada, sem
presumir a página 1. Fonte inválida/incompleta, gabarito não revisado, regra/schema
não suportado ou legado sem snapshot têm motivo de indisponibilidade e N/A.

`MeasureValidation` lê uma execução finalizada e um SHA de gabarito explícito,
calcula ambas as medições e persiste comparações de tipos END_TO_END e
EVALUATOR_ISOLATED, com regra/comparador/versão/hash e quantidade avaliada ou
indisponível. IDs estáveis permitem repetição sem sobrescrever comparação.
Não escreve observações/avaliações/configuração do processamento. Reexecuções
ativas são rejeitadas para não ler páginas em transição. Dados legados mantêm
o tipo padrão e continuam legíveis.

Testes verificam os 90 inputs canônicos (18 cenários × cinco analitos), troca
apenas da resposta esperada sem mudar a saída real do avaliador e troca de versão
do gabarito sem reprocessar a execução. No S25, um PDF privado sintético percorreu
texto posicionado → parser → repositório → medição: classificação 5/5 e campos
brutos 20/20; o ensaio canônico isolado também foi 5/5. Ao mudar somente um valor
do gabarito em nova versão, a nova comparação da mesma extração resultou em 4/5 enquanto a saída
automática persistida permaneceu idêntica após reabertura. Os dois pares de
comparações/gabaritos foram preservados. É controle técnico, sem cobertura de
corpus completo via OCR/LLM nem validação clínica.

## Relatórios locais por estrato (6.4)

`GenerateLocalValidationReport` lê execuções finalizadas e SHAs de gabarito
selecionados explicitamente. Não usa automaticamente o gabarito mais recente,
não aplica correções e não altera comparações ou saídas persistidas. Hilt fornece
o caso de uso para a futura tela de relatório.

`ValidationReportGenerator` agrega contagens por origem, formato, analito,
layout/versão e partição. Famílias distintas são contadas separadamente das
variantes; dividir uma família entre desenvolvimento e avaliação é rejeitado.
Analitos desconhecidos aparecem em UNSUPPORTED para não esconder extras.
Versões de extrator/parser/regra/gabarito/schema, configuração de inferência,
modo e base AUTOMATIC/CORRECTED formam grupos independentes. Cada entrada conserva
runId, hash documental, família, hash/versão de gabarito e aparelho/Android.

Somam-se numeradores e denominadores, nunca médias de percentuais. O JSON
`validation-report/1` conserva essas frações; denominador zero tem `ratio == null`
na leitura Kotlin e deve ser exibido como N/A. Um relatório sem entradas tem
listas vazias, sem alegação de acerto. Para grupos existentes, origem pública,
formatos e partições sem amostras têm linhas vazias explícitas. Gabaritos pendentes
ou sem metadados válidos de revisão não participam dos denominadores; suas
quantidades permanecem visíveis. N/A do avaliador isolado inclui os motivos.

Snapshots corrigidos podem ser agregados pela API pura somente com base
CORRECTED e IDs explícitos de correção; exigem avaliações fornecidas para esse
snapshot. Apenas a existência de correções nunca melhora a métrica automática.
A criação/revisão dessas projeções pela UI permanece na tarefa 7.3. O relatório
é retornado em memória, sem exportação ou gravação em claro. IDs, estratos e
agregados ainda são dados privados; persistência futura deve usar o store protegido.
Não contém textos brutos de exames ou pensamentos do modelo.

Os testes verificam fração agregada 1/4 em vez da média de 1/1 e 0/3,
fora→dentro, pendências, N/A, versões, partições/famílias, extras desconhecidos,
round-trip JSON, origem de saídas e leitura somente da execução/gabarito pedidos.
O teste instrumentado de PDF privado também gera relatório após reabrir a base,
preservando 5/5 para o gabarito v2 e 4/5 para v3 em grupos separados.
