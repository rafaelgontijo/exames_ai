# Design

## Context

Veja [proposal.md](./proposal.md) para motivação e escopo. Hoje no código:
- A extração de exames (`ExamViewModel.startExtraction`) executa `ExamRunner.extract(...)` dentro do `viewModelScope`, sendo interrompida se o processo de UI for encerrado ou suspenso em segundo plano.
- O download sob demanda (`ModelDownloadWorker` em `:inference-local`) já usa `WorkManager` (`CoroutineWorker`), mas ainda não chama `setForeground(ForegroundInfo(...))`, não cria canais de notificação nem emite notificação de conclusão/falha.
- `app/src/main/AndroidManifest.xml` ainda não declara `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` nem `FOREGROUND_SERVICE_DATA_SYNC`, e `inference-local/src/main/AndroidManifest.xml` declara `android:stopWithTask="true"` no `InferenceProcessService` (`:inference`), o que encerraria a inferência local caso o usuário deslize o app da lista de recentes.
- `NoRestorableClinicalStateTest` proíbe `SavedStateHandle`, `rememberSaveable`, `onSaveInstanceState`, `putString(`, `mapSaver` e `listSaver` em `app/src/main` e `core-ui/src/main`.

## Goals / Non-Goals

**Goals:**
- Garantir que **extração de exames** (local ou fallback privado na nuvem `opt-in`) e **download de pesos locais** continuem em segundo plano sob `WorkManager` + Foreground Service (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`), compatíveis com Android 14/15/16/17 (`targetSdk = 36`).
- Exibir barra de progresso nativa, botão de **Cancelar** na notificação contínua e notificação de conclusão/falha clicável sem vazar PII, nomes de arquivo, valores laboratoriais ou linguagem diagnóstica.
- Manter sincronização em tempo real entre o `WorkManager` e `ExamViewModel` / `ProcessingScreen` / `HelpScreen` (inclusive ao reabrir o app com tarefa em andamento).
- Solicitar `POST_NOTIFICATIONS` no Android 13+ de maneira não bloqueante.

**Non-Goals:**
- Processamento paralelo simultâneo de múltiplos PDFs (mantemos fila serializada com trabalho único `ExistingWorkPolicy.KEEP` ou fila `APPEND_OR_REPLACE` simples para evitar OOM no processo `:inference` e simplificar a UX).
- Notificações com resumo clínico, contagem de alterações ou recomendações (estritamente proibido por `AGENTS.md`).
- Persistência de `ClinicalContextUi` no `WorkManager` `Data` (o contexto clínico continua 100% efêmero em RAM na sessão da UI).

## Decisions

### 1. `WorkManager` com `ForegroundInfo(dataSync)` para ambas as tarefas
- **Escolha:** Usar `CoroutineWorker` com `setForeground(createForegroundInfo(...))` e `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` tanto no novo `ExamExtractionWorker` (`:app`) quanto no `ModelDownloadWorker` (`:inference-local`), declarando `<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge" />` no `app/src/main/AndroidManifest.xml`.
- **Justificativa:** `WorkManager` já é dependência do projeto (`androidx.work:work-runtime-ktx`), gerencia fila, `WorkInfo` reativo (`Flow<List<WorkInfo>>`), `createCancelPendingIntent(id)` nativo para o botão **Cancelar** da notificação e limpeza automática do `SystemForegroundService` ao encerrar ou cancelar.
- **Alternativa descartada:** Criar dois `Service` customizados com binder próprio duplicaria gestão de ciclo de vida, cancelamento e reconexão com a UI.

### 2. Arquitetura de notificações e canais (2 canais + IDs fixos)
- **Canais (`NotificationChannel` em API 26+):**
  1. `exames_background_progress` (`IMPORTANCE_LOW`, silencioso, sem vibração, `lockscreenVisibility = VISIBILITY_PRIVATE`): usado pelas notificações contínuas (`ongoing = true`, `onlyAlertOnce = true`) de progresso do exame (`NOTIFICATION_ID_EXAM_PROGRESS = 1001`) e do download do modelo (`NOTIFICATION_ID_MODEL_PROGRESS = 2001`).
  2. `exames_background_alerts` (`IMPORTANCE_DEFAULT`, `lockscreenVisibility = VISIBILITY_PRIVATE`): usado pelas notificações finais de conclusão/falha (`autoCancel = true`) do exame (`NOTIFICATION_ID_EXAM_RESULT = 1002`) e do download do modelo (`NOTIFICATION_ID_MODEL_RESULT = 2002`).
- **Throttling de atualização:** Atualizar `setForeground` / `setProgress` no máximo a cada ~500 ms ou mudança de percentual/página inteira, evitando rate-limiting do `NotificationManager` no Android 14+.
- **Ação Cancelar nativa:** Usar `WorkManager.getInstance(context).createCancelPendingIntent(id)` diretamente na notificação de progresso — cancela a coroutine do worker (propagando `CancellationException` para o processo `:inference` ou `OkHttp Call.cancel()`), limpa arquivos temporários / marca o registro ativo via `store.cancelActive(examId)` e descarta a notificação contínua.

### 3. Dados trafegados no `WorkManager` `Data` e conformidade com `NoRestorableClinicalStateTest`
- **Escolha:**
  - `ExamViewModel` chama `store.accept(uri)` antes de enfileirar o worker (validando PDF e gravando a cópia privada cifrada sob `examId` opaco — UUID).
  - O `ExamExtractionWorker` recebe **somente** `workDataOf(KEY_EXAM_ID to examId, KEY_USE_CLOUD to useCloud)` — **zero** URI externa, **zero** nome de arquivo, **zero** conteúdo clínico e **zero** uso de `putString(` em `:app` (mantendo `NoRestorableClinicalStateTest` verde).
  - Progresso reportado via `workDataOf(KEY_STAGE to ..., KEY_PAGE to page, KEY_TOTAL_PAGES to totalPages, KEY_BLOCK to block, KEY_TOTAL_BLOCKS to totalBlocks, KEY_COMPLETED_PAGES to intArray, KEY_PAGE_COUNTS to intArray, KEY_PAGE_ISSUES to booleanArray)`.
  - Saída final reportada via `workDataOf(KEY_EXAM_ID to examId, KEY_ERROR_CODE to errorCode)` — os resultados completos ficam exclusivamente no `EncryptedExamHistoryStore` cifrado via Keystore.

### 4. Deep-link seguro por `examId` opaco e reconexão da UI
- **Toque na notificação de progresso:** `PendingIntent` (`FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`) abre `MainActivity` com `Intent.FLAG_ACTIVITY_SINGLE_TOP` levando apenas o destino (`EXTRA_OPEN_DESTINATION = "processing"` ou `"help"`).
- **Toque na notificação de conclusão do exame:** `PendingIntent` abre `MainActivity` com `EXTRA_OPEN_EXAM_ID = examId` (validado por `AppRoutes.isOpaqueId(examId)`). `MainActivity` (`onCreate` + `onNewIntent` com `launchMode="singleTop"`) encaminha o `examId` ao `ExamViewModel.openExam(examId)` e limpa o extra da `Intent`.
- **Reconexão automática:** No `init` do `ExamViewModel`, observar `WorkManager.getWorkInfosForUniqueWorkFlow(EXAM_EXTRACTION_WORK_NAME)`. Se o app for reaberto com uma extração `RUNNING`/`ENQUEUED`, a `ProcessingScreen` volta a refletir o progresso real; se transicionar para `SUCCEEDED` enquanto o app estiver aberto na tela de processamento, navega automaticamente para `ResultsScreen` (e dispensa a notificação redundante ou mantém consistência com o histórico).

### 5. Ciclo de vida do processo `:inference` em background
- **Escolha:** Alterar `android:stopWithTask="false"` no `InferenceProcessService` em `inference-local/src/main/AndroidManifest.xml`, garantindo que deslizar o app dos recentes não mate o processo `:inference` no meio de uma faixa enquanto o `ExamExtractionWorker` está ativo como Foreground Service. O processo `:inference` continua sendo criado sob demanda por faixa e encerrado imediatamente ao fim de cada faixa ou em cancelamento cooperativo.

### 6. Permissão `POST_NOTIFICATIONS` (Android 13+) e fallback em falha de `setForeground`
- **Escolha:** Em `MainActivity`, usar `ActivityResultContracts.RequestPermission()` para solicitar `Manifest.permission.POST_NOTIFICATIONS` em API >= 33 na primeira vez que o usuário inicia uma extração ou download de modelo (antes de enfileirar, sem travar caso negada).
- **Resiliência:** Se `setForeground(...)` lançar `ForegroundServiceStartNotAllowedException` (restrição rara de background no Android 12+/14+) ou se `POST_NOTIFICATIONS` estiver negada, o worker captura a exceção de notificação de forma segura, continua executando o trabalho normalmente e atualiza o progresso no `WorkInfo` para a UI do app.

## Risks / Trade-offs

- **[Android 14+ limite de tempo de `dataSync` (6h em 24h) / restrições de Foreground Service]** → Extrações de laudos duram de segundos a poucos minutos e o download de 2,58 GB dura poucos minutos em Wi-Fi, ficando muito abaixo do teto de 6 horas. Além disso, o worker inicia a partir de ação explícita do usuário em primeiro plano (estado elegível para `FGS`).
- **[Cancelamento enquanto o app está fechado deixa registro `interrupted` ou cópia ativa no `EncryptedExamHistoryStore`]** → O `ExamExtractionWorker` envolve `runner.extract(...)` em `try / catch (CancellationException)` executando `withContext(NonCancellable) { store.cancelActive(examId) }` antes de relançar, removendo o registro incompleto e a notificação contínua.
- **[Duplicação de notificação quando usuário está olhando a `ProcessingScreen`]** → Quando a extração termina, a notificação contínua é encerrada automaticamente pelo fim do `ForegroundInfo`; a notificação de conclusão (`autoCancel = true`) informa o usuário caso tenha saído do app e é cancelada automaticamente se o usuário já estiver com aquele `examId` aberto em `ResultsScreen`.
- **[Injeção de `CloudAuthTokenProvider` dentro do `ExamExtractionWorker`]** → Como `ExamExtractionWorker` constrói `ExamRunner` via fábrica/provedor compartilhado (mesmo `BuildConfig` + `AppPreferencesStore` + `LocalTextInference` + `CloudFallbackInference`), mantém paridade exata entre execução em foreground e background sem persistir tokens em `WorkManager` `Data`.

## Migration Plan

1. Mudança puramente aditiva no cliente Android (`:app`, `:inference-local`, `:core-ui`); sem impacto em `:domain` nem no backend AWS.
2. Compatível retroativamente com exames já salvos no `EncryptedExamHistoryStore` e preferências existentes em `AppPreferencesStore`.
3. Rollback trivial via reversão de PR sem migração de schema de dados.
