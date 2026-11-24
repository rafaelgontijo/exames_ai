# Tasks

## 1. Permissões, Manifesto e Infraestrutura de Notificações

- [x] 1.1 Atualizar `app/src/main/AndroidManifest.xml` (declarar `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `android:launchMode="singleTop"` na `MainActivity` e `<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge" />`) e atualizar `inference-local/src/main/AndroidManifest.xml` (`android:stopWithTask="false"` em `InferenceProcessService`), verificando o manifesto mesclado com `./gradlew :app:processDebugManifest`.
- [x] 1.2 Criar utilitário de canais e construção de notificações em `:inference-local` (compartilhável entre `ModelDownloadWorker` e `:app`, ou centralizado em helper dedicado) definindo os canais `exames_background_progress` (`IMPORTANCE_LOW`, silencioso, `VISIBILITY_PRIVATE`) e `exames_background_alerts` (`IMPORTANCE_DEFAULT`, `VISIBILITY_PRIVATE`), garantindo redação estritamente neutra sem PII/dados clínicos/linguagem diagnóstica e verificação por testes unitários de contrato de texto e progresso (`./gradlew :inference-local:testDebugUnitTest`).

## 2. Download de Pesos Locais em Foreground Service com Notificação (`:inference-local`)

- [x] 2.1 Evoluir `ModelDownloadWorker` em `inference-local/src/main/kotlin/br/com/exames/inferencelocal/OnDemandModelDownloader.kt` para promover `setForeground(ForegroundInfo(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC))` com barra de progresso (percentual e MB baixados, atualização com throttling), botão nativo **Cancelar** (`createCancelPendingIntent`), tratamento gracioso de `ForegroundServiceStartNotAllowedException`/permissão negada e emissão de notificação final neutra ao concluir (SHA-256 aprovado) ou falhar (espaço/integridade/rede), verificando com testes unitários em `:inference-local` (`./gradlew :inference-local:testDebugUnitTest`).

## 3. Processamento de Exames em Segundo Plano com `WorkManager` e Notificações (`:app`)

- [x] 3.1 Implementar `ExamExtractionWorker` e `ExamBackgroundScheduler` em `:app` recebendo apenas `examId` opaco e flags booleanas sem PII via `workDataOf(...)` (sem `putString(`, mantendo `NoRestorableClinicalStateTest` verde), promovendo `ForegroundInfo(dataSync)` com barra de progresso por página/bloco e botão **Cancelar**, limpando registro ativo via `withContext(NonCancellable) { store.cancelActive(examId) }` em caso de cancelamento e emitindo notificação final neutra clicável com `EXTRA_OPEN_EXAM_ID` ao concluir ou aviso operacional ao falhar, com testes unitários cobrindo serialização de progresso, limpeza em cancelamento e ausência de PII/dados clínicos nas notificações (`./gradlew :app:testDebugUnitTest`).
- [x] 3.2 Refatorar `ExamViewModel` em `app/src/main/kotlin/br/com/exames/app/ExamViewModel.kt` para delegar a execução da extração ao `ExamBackgroundScheduler` após `store.accept(uri)`, observar `WorkManager.getWorkInfosForUniqueWorkFlow(...)` no `init` (mantendo `ProcessingScreen` sincronizada em tempo real e restaurando o acompanhamento ao reabrir o app), cancelar o worker via `cancelUniqueWork` e abrir automaticamente `ResultsScreen` ao concluir com o app aberto, verificando com `./gradlew :app:testDebugUnitTest` (incluindo `NoRestorableClinicalStateTest`).

## 4. Permissão `POST_NOTIFICATIONS`, Deep-Link Seguro e UX (`:app`, `:core-ui`)

- [x] 4.1 Atualizar `MainActivity.kt` para solicitar `POST_NOTIFICATIONS` no Android 13+ via `ActivityResultContracts.RequestPermission()` de forma não bloqueante ao iniciar extração ou download, e tratar `onCreate`/`onNewIntent` com `EXTRA_OPEN_EXAM_ID` (validado via `AppRoutes.isOpaqueId`) e `EXTRA_OPEN_DESTINATION` (`processing`/`help`) limpando os extras após o consumo, verificando compilação e testes unitários (`./gradlew :app:testDebugUnitTest`).
- [x] 4.2 Atualizar `ProcessingScreen.kt` e `HelpScreen.kt` em `:core-ui` com dica discreta de que o acompanhamento continua pelo menu de notificações caso o usuário saia do aplicativo (preservando os limites éticos, contraste e alvos ≥ 48 dp), verificando com `./gradlew :core-ui:test :ui-checks:compileDebugAndroidTestKotlin`.

## 5. Verificação Integrada End-to-End

- [x] 5.1 Executar suíte completa de testes (`./gradlew test assembleDebug`), auditar o manifesto mesclado (`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_DATA_SYNC`, `allowBackup="false"`, `usesCleartextTraffic="false"`) e validar no Galaxy S25 (`:ui-checks:connectedDebugAndroidTest`, `:app:connectedDebugAndroidTest` e ensaio manual/instrumentado de extração em segundo plano com barra de progresso e notificação final clicável).
