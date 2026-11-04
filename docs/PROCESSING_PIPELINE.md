# Processamento recuperável por execução

`ResumeProcessingRun` trabalha sobre runId/configuração imutáveis, sem gabarito
ou correções posteriores. `ProcessingWorker` recebe somente um runId UUID/hex
opaco em WorkData. Usa trabalho único com KEEP e nenhuma restrição de rede.
Hilt fornece uma instância singleton do executor, com exclusão mútua; a leitura
privada também mantém somente uma página ativa.

O adaptador `PrivateRunPages` autentica a cópia privada, resolve a quantidade de
páginas PDF ou imagem e aplica os limites de 1–20 páginas. Sua identidade é
`private-page-pipeline/1`; não aceita regra/parser diferentes dos implementados.
Páginas percorrem texto PDF/OCR → parser → validação opcional → avaliação. O
bitmap/sessão são fechados antes de retornar o lote para persistência.

O executor grava cada lote por `ValidationRepository.commitPage`, na mesma
transação que o checkpoint `(runId, page)`. O repositório confere documento,
modo, estado e elegibilidade para impedir escritores sobre tombstones. Ao
retomar QUEUED/RUNNING, o executor pula checkpoints COMPLETE, inclusive quando
o processo morreu depois do commit mas antes de receber sua confirmação ou de
finalizar o run. Uma execução terminal não é reprocessada. Mudanças de modo ou
versões exigem outra execução; misturar checkpoints é erro.

Um erro de processamento em uma página produz checkpoint FAILED com código
técnico fixo, sem mensagem de exceção. As demais páginas continuam. O estado
final é COMPLETE, PARTIAL ou FAILED conforme a quantidade concluída. Falha de
persistência não vira erro de página: o worker solicita retentativa e a próxima
execução consulta o estado realmente confirmado. Parada/cancelamento pelo
scheduler propaga CancellationException e deixa o run retomável, sem gravar
uma resposta tardia. Não promete execução durante force-stop.

Checkpoints novos guardam a participação de inferência. Checkpoint legado sem
essa informação nunca autoriza alegar sucesso de IA. Somente sucesso explícito
em todas as páginas permite SUCCEEDED no run; falhas/fallbacks continuam
distintos de sucesso do parser. Duração por página usa elapsedRealtime e não o
horário civil; é duração total da etapa, não benchmark ou pico de memória.

## Limites ainda pendentes

O agendamento de produção aceita somente BASELINE nesta etapa. O fluxo do
executor suporta ambos os modos e testa retomada/participação por modo, mas o
adaptador assistido só será liberado após os gates de limites do runtime e
protocolo (4.10/4.12). A configuração e o diário protegido foram implementados
posteriormente em 4.13; ver [INFERENCE_TRACE.md](INFERENCE_TRACE.md). Não é
substituição da entrega P0 de inferência: 4.8, 4.10 e 4.12 continuam pendentes.
Não houve novo teste no S25.

Os controles de 5.2 estão descritos abaixo. Exclusão, auditoria e recuperação
estão em `DELETION_COORDINATION.md`, `DERIVATIVE_AUDIT.md` e `PROCESSING_RECOVERY.md`.
A UI de importação/agendamento permanece em
7.1. Os testes de retomada não demonstram que JNI/OCR possam ser interrompidos
imediatamente.

## Verificação executada

Oito testes JVM cobrem confirmação de commit perdida, interrupção antes da
finalização, concorrência, cancelamento/resposta tardia, falha parcial/total,
IDs/modos/configuração, limites de páginas e participação de IA legada.
Dois instrumentados conferem WorkData/constraints e o worker sobre PDF privado
de duas páginas: preserva cinco observações da página 1 após reabrir a base,
conclui dez sem duplicação e não reprocessa execução terminal.

O ensaio opt-in em duas invocações separadas também passou no emulador API 36,
com PIDs 12929/12980. A interrupção logo após commit é injetada; depois o host
emite force-stop e inicia outra instrumentação sem reinstalar. A segunda
invocação confere PID diferente, IDs originais, dez observações e dois
checkpoints. Não é um SIGKILL dentro da transação Room nem retomada automática
durante force-stop. Original e registros permanecem cifrados; o marcador de
teste contém somente nomes operacionais, IDs e PID. Limpeza remove artefatos,
chave/base de teste, marcador e pacote criado pelo controlador.

Reprodução em emulador disponível:

```sh
./gradlew :data-local:assembleDebugAndroidTest
python3 tools/processing/run_recovery.py --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5554 --report /caminho/novo-relatorio.json
```

O controlador recusa substituir relatórios anteriores e não altera outros
pacotes. Falha conserva a fixture sintética para investigação, sem imprimir
corpos de exceção da instrumentação. Evidência agregada:
`validation/results/worker-process-recovery-emulator-2026-10-27.json`.

## Cancelamento, retentativa e órfãos (5.2)

`ManageProcessingRuns.cancel` confirma CANCELLED no repositório antes de pedir
cancelamento do trabalho único. `stopRunIfActive` realiza a leitura/checagem e a
transição na mesma transação Room: uma corrida não altera execução já terminal.
Somente QUEUED/RUNNING são canceláveis; páginas já concluídas permanecem
consultáveis. Callbacks tardios encontram a barreira de estado no executor e
na transação de commit. CANCELLED não pode voltar a RUNNING.

Parada do scheduler sem comando explícito mantém a execução retomável; o comando
explícito é durável e não é confundido com essa interrupção. Se houver interrupção
entre confirmar CANCELLED e cancelar WorkManager, a reconciliação cancela o
trabalho remanescente. Isso não equivale a interrupção imediata de JNI/OCR.

`retry(parentRunId, retryRunId)` aceita PARTIAL/FAILED/CANCELLED e cria execução
filha QUEUED com ID opaco fornecido pelo chamador, fonte/hash/configuração iguais,
ambiente atual e novo horário. Não consulta gabaritos nem copia/edita páginas,
observações ou avaliações anteriores. Reprocessa o documento inteiro, incluindo
a página que falhou; não fabrica reparo se a fonte continuar inválida. Novas
observações recebem IDs próprios. Repetir o mesmo retryRunId é idempotente e não
reagenda filha terminal. Nova tentativa deliberada deve receber novo ID.
Configuração sem suporte exige outra execução compatível, sem troca silenciosa.

`ProcessingReconciliationWorker` roda na abertura e periodicamente, sem rede.
Reagenda QUEUED/RUNNING com KEEP, reparando morte entre createRun e enqueue;
cancela trabalhos etiquetados cujas execuções foram canceladas/finalizadas ou
removidas. Configuração incompatível fica FAILED com código técnico explícito,
sem ficar órfã ou acionar inferência não aceita. Falha de chave/banco não implica
ausência: a reconciliação falha e retenta, sem cancelar por leitura indeterminada.

O worker retenta falhas globais até a terceira tentativa; depois tenta confirmar
FAILED/WORKER_FAILURE. Se a chave/base ainda impedir essa confirmação, continua
retentando sem recriar banco ou descartar dados inacessíveis. Códigos são enums
fixos, sem texto de exceção. Falhas locais de página não impedem as outras:
PARTIAL identifica resultados válidos com páginas FAILED. Páginas sem layout ou
observações suportadas falham explicitamente, com warnings operacionais, sem
contar como extração concluída de uma página vazia. Estados clínicos como
NEEDS_REVIEW/NOT_CLASSIFIABLE continuam resultados válidos do processamento.

Sete testes JVM dos controles verificam ordem durável, idempotência, falha de
agendamento, reconciliação repetida, configurações/IDs/fontes inválidas e resposta
tardia após cancelamento. Quatro instrumentados usam PDF com stream inválido na
página intermediária, preservam páginas 1/3, retentam em filha sem alterar a
original, rejeitam commit tardio após reabrir a base, recuperam órfã com WorkManager
real, cancelam trabalho enfileirado e confirmam falha global após retentativas.
Somente fixtures sintéticas; sem execução nova de modelo no S25.
