# Proposal

## Why

Atualmente, a extração de laudos em PDF ocorre vinculada ao `viewModelScope` da tela ativa e pode ser interrompida quando o usuário minimiza o aplicativo, bloqueia a tela ou alterna para outro app durante análises mais longas (especialmente em PDFs multipágina locais). Além disso, o download sob demanda dos pesos locais do Gemma 4 E2B (~2,58 GB) via `WorkManager` ainda não promove um Foreground Service nem exibe acompanhamento contínuo na bandeja de notificações do Android, deixando o usuário sem visibilidade do progresso nem aviso quando a extração ou o download termina.

## What Changes

- **Processamento de exames em segundo plano com Foreground Service (`WorkManager`):** mover a execução de `ExamRunner.extract(...)` para um `CoroutineWorker` dedicado com `ForegroundInfo` (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`), permitindo que a leitura de PDFs (tanto via IA local quanto via fallback privado na nuvem `opt-in`) continue com segurança mesmo com o app em segundo plano ou tela bloqueada.
- **Download de pesos locais promovido a Foreground Service com notificação:** atualizar `ModelDownloadWorker` em `:inference-local` para promover `ForegroundInfo` (`dataSync`) durante a transferência HTTPS e verificação SHA-256, evitando encerramentos prematuros pelo sistema operacional em downloads longos de ~2,58 GB.
- **Notificações nativas de progresso e conclusão (com privacidade estrita):**
  - Criar canais de notificação dedicados (progresso silencioso `IMPORTANCE_LOW` e alertas de conclusão/falha `IMPORTANCE_DEFAULT`).
  - Exibir barra de progresso nativa no menu de notificações para ambas as operações (página/bloco e percentual na extração do exame; percentual e MB baixados no modelo de IA) com botão direto de **Cancelar** na própria notificação.
  - Emitir notificação de finalização (sucesso ou falha operacional) ao término de cada tarefa: tocar na notificação de exame concluído abre o aplicativo diretamente na tela de resultados daquele `examId` opaco; tocar na notificação do modelo local abre o app com o status pronto para uso offline.
  - Aplicar `VISIBILITY_PRIVATE` e redação estritamente neutra (nunca exibir nome do paciente, nome do arquivo PDF, valores laboratoriais, contagens alarmistas ou linguagem diagnóstica nas notificações ou tela de bloqueio).
- **Permissão `POST_NOTIFICATIONS` graciosa (Android 13+):** declarar `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` e `FOREGROUND_SERVICE_DATA_SYNC` no manifesto e solicitar permissão em tempo de execução sem bloquear o processamento nem o download caso o usuário recuse.
- **Sincronização reativa com a UI:** fazer o `ExamViewModel` observar o `WorkInfo` ativo do `WorkManager` para manter a `ProcessingScreen` sincronizada em tempo real (inclusive ao reabrir o app com uma extração em andamento) e atualizar o histórico automaticamente ao finalizar.

## Capabilities

### New Capabilities
- `background-execution-notifications`: Execução persistente em segundo plano do processamento de exames (local ou fallback privado) via Foreground Service, com acompanhamento em barra de progresso na bandeja de notificações do Android, ação de cancelamento, notificação neutra ao concluir/falhar, deep-link seguro por `examId` opaco e tratamento gracioso de `POST_NOTIFICATIONS`.

### Modified Capabilities
- `on-demand-model-delivery`: Ampliar o requisito de controle do ciclo de vida do download em segundo plano para exigir promoção a Foreground Service (`dataSync`), notificação contínua com barra de progresso e ação de cancelamento no menu de notificações do Android e notificação ao concluir ou falhar a verificação SHA-256.

## Impact

- **Módulos afetados:** `:app` (`AndroidManifest.xml`, novo `ExamExtractionWorker`, `NotificationHelper`/canais, `ExamViewModel`, `MainActivity` com tratamento de `Intent` para `examId` opaco e pedido de `POST_NOTIFICATIONS`), `:inference-local` (`ModelDownloadWorker`, `LocalModelStore`, notificação de progresso/conclusão de download, revisão de `stopWithTask` em `InferenceProcessService` para execução sob Foreground Service), `:core-ui` (pequenos ajustes de mensagem/dica na `ProcessingScreen` e `HelpScreen` informando que o usuário pode sair do app e acompanhar pela notificação).
- **Dependências:** já presentes no projeto (`androidx.work:work-runtime-ktx`, `androidx.core:core-ktx` com `NotificationCompat` e `NotificationManagerCompat`) — nenhuma nova biblioteca de terceiros necessária.
- **Governança e privacidade (`AGENTS.md`):** `:domain` permanece 100% JVM puro; `WorkManager` `Data` recebe apenas identificadores opacos (`examId`) e flags técnicas sem PII (nunca texto clínico, URIs ou contexto clínico efêmero); `NoRestorableClinicalStateTest` permanece verde; notificações jamais expõem dados clínicos ou linguagem diagnóstica.
