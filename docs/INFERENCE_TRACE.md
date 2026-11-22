# Rastreabilidade protegida da inferência

A execução preserva sua `InferenceConfiguration` cifrada e imutável. O adaptador
declara modelo/revisão/hash, quantização declarada, coordenada/hash do AAR,
prompt/schema, GPU, sampler, contexto/saída, cache, canais e limites de processo.
`PrivateRunPages` exige igualdade integral dessa declaração com a configuração
salva, além do gate independente de aceitação assistida. Atualizar modelo,
runtime, protocolo ou política não retoma uma execução antiga sob outra identidade.

`SelectedInferenceConfiguration` centraliza as constantes usadas pelo adaptador
Java. O teste JVM confronta a declaração com `selection.json`, o lock do modelo
e a coordenada/SHA-256 do AAR realmente resolvido pelo Gradle. A declaração de
quantização preserva a ressalva do lock; não inventa uma quantização uniforme.
O protocolo de produção é `biochemistry-evidence/1`; o prompt de controle antigo
em `selection.json` continua identificado como evidência histórica do spike.

## Diário antes da chamada

Antes de chamar `TextInference.generate`, o pipeline confirma uma transação
`startInferenceAttempt` com ID opaco novo, configuração e horário civil de início.
Ela grava estado STARTED no payload AES-GCM do checkpoint, vinculado por AAD ao
run/página. STARTED significa intenção durável; não afirma que JNI ou geração
começaram. Falha de gravação impede a chamada ao adaptador. BASELINE e rejeição
por tamanho durante a compilação do prompt não iniciam uma chamada.

Na transação de `commitPage`, a tentativa vira COMPLETED junto das observações,
avaliações e checkpoint. Preserva resultado/fallback, motivo fixo de falha,
issues de evidência e tempos de carga/geração disponíveis. `requestSubmitted`
indica chamada ao adaptador; somente ACCEPTED após schema/evidência pode ser
SUCCEEDED. BUSY, ausência de pesos, limites e timeout não demonstram geração
nativa bem-sucedida. O repositório rejeita identidade, resultado ou intenção
incompatíveis. Uma página completa não pode ter sua saída ou trace substituídos.

Uma intenção sem conclusão permanece identificável após reabertura. Antes de
uma nova submissão na mesma página, ela é preservada como INTERRUPTED, sem
inventar duração ou resultado anterior. CANCELLED é registrado no cancelamento
cooperativo e na transação de parada do run, inclusive se a página já retornou e
ainda não foi publicada. Cancelar não publica resultados. Uma falha operacional
terminal preserva intenções pendentes como interrompidas.

O histórico admite até 128 intenções por página; exceder essa barreira falha antes
da chamada e conserva todas as anteriores. Repetir a mesma gravação inicial é
idempotente. Timestamps civis não são usados para calcular latência: os tempos
disponíveis vêm de relógios monotônicos; em interrupção ficam desconhecidos.

## Proteção e compatibilidade

O diário e o resumo da tentativa ficam no payload protegido de `page_checkpoints`;
não há nova coluna sensível nem alteração estrutural do Room 3. Campos opcionais
permitem ler checkpoints anteriores sem fabricar identidade, tentativa ou
sucesso. Metadados em claro continuam limitados a IDs, página e estado operacional.

Não se aceitam prompt, resposta, raciocínio, URI ou mensagem de exceção nos tipos
do diário. O canal final fica apenas em memória até ser validado. Arquivos de
cache clínico em claro e logs de conteúdo não foram adicionados; cache nativo
continua `:nocache`. As fontes/campos aceitos seguem a persistência clínica cifrada
existente. Durações de initialize/send não são a duração total nem pico de RAM.

Tombstone e elegibilidade são verificadas nas transações. Exclusão em cascata
remove checkpoint, resumo e histórico; callbacks tardios não os recriam. A
limpeza dos artefatos mantém a barreira de escritores e preserva pesos
compartilhados. Falha de chave não gera registros substitutos em claro.

## Evidência e limites

Testes JVM cobrem serialização, legado, combinações inválidas, barreiras antes da
chamada e configuração divergente. `InferenceTraceTest` usa PDF privado real e
adaptador simulado no emulador: reabertura, atualização de identidade, intenção
inconclusa, cancelamento, final tardio e limite de histórico. `DeletionCoverageTest`
inclui diário/resumo cifrados com canário dinâmico, exclusão de derivados,
escritores tardios e preservação de pesos de controle.

Esses controles verificam persistência e coordenação. A retomada de intenção foi
ensaiada por reabertura de banco; não é uma nova medição de morte de processo/JNI.
Nenhum deles executa Gemma. Aceite do modelo no processo novo, segmentação,
token budget e ativação assistida permanecem nas tarefas correspondentes, com
S25 reservado para a etapa final. Não constituem validação clínica.
