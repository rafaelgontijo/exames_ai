# Tasks

## 1. Governança, Permissões e Estrutura de Módulos

- [x] 1.1 Atualizar `AGENTS.md` e `app/src/main/AndroidManifest.xml` para permitir `INTERNET` e `ACCESS_NETWORK_STATE` restritos ao download verificado dos pesos e ao fallback privado opt-in (mantendo `usesCleartextTraffic="false"`, `allowBackup="false"`, zero telemetria e `:domain` puro JVM), verificando com auditoria do manifesto mesclado (`./gradlew :app:processDebugManifest`).
- [x] 1.2 Desacoplar o empacotamento obrigatório de 2,58 GB em `app/build.gradle.kts` para tornar `stageBundledModel` opcional via `-PbundleLocalModel=true`, adicionar `:inference-cloud` em `settings.gradle.kts` e declarar dependências mínimas de rede/background em `gradle/libs.versions.toml` (OkHttp/WorkManager fora de `:domain`), verificando com `./gradlew :app:assembleDebug` gerando APK leve sem `assets/models/gemma4.litertlm`.

## 2. Domínio Puro: Motor de Decisão e Detecção Local de PII (`:domain`)

- [x] 2.1 Implementar `InferenceRoutingDecisionEngine`, `DeviceCapabilitySnapshot`, `LocalWeightsState`, `CloudFallbackPolicy`, `InferenceExecutionTarget` e novos códigos operacionais em `InferenceFailure` no módulo `:domain`, com testes unitários cobrindo limites de RAM (6 GB total / 2,5 GB disponíveis), GPU/ABI 64-bit, estado de download, opt-in e recusa offline, verificando com `./gradlew :domain:test`.
- [x] 2.2 Implementar `PiiRegionDetector` puro JVM em `:domain` para identificar `SourceRegion` de CPF, e-mail, telefone e campos/linhas administrativas de identificação do paciente em `List<SourceFragment>` sem mascarar linhas de medição laboratorial, com testes unitários de limites, falsos positivos e páginas vazias, verificando com `./gradlew :domain:test`.

## 3. Redação Visual Local em Memória e Entrega Sob Demanda de Pesos (`:data-private-pdf`, `:inference-local`)

- [x] 3.1 Estender `PdfPageBandSlicer` em `:data-private-pdf` para aceitar `redactionRegions: List<SourceRegion>` e pintar tarjas opacas sobre as regiões de PII no bitmap recortado em RAM antes da compressão PNG/JPEG ≤ 512 KiB (sem alterar o bitmap original nem gravar arquivos), com testes unitários verificando cobertura das caixas e preservação fora da tarja (`./gradlew :data-private-pdf:testDebugUnitTest`).
- [x] 3.2 Implementar `AndroidDeviceCapabilityProbe` e evoluir `BundledModelStore` para `LocalModelStore` + `OnDemandModelDownloader` (download HTTPS em background via `WorkManager`/coroutines, restrição opcional a Wi-Fi, verificação de espaço livre, streaming SHA-256 em `.partial` com `Os.rename` atômico, cancelamento/retomada e remoção local) em `:inference-local`, com testes unitários cobrindo corrupção de hash, espaço insuficiente e cancelamento (`./gradlew :inference-local:testDebugUnitTest`).

## 4. Cliente HTTPS de Fallback e Orquestração Híbrida (`:inference-cloud`, `:app`)

- [x] 4.1 Implementar módulo `:inference-cloud` com `CloudFallbackInference : TextInference` (OkHttp com `cache(null)`, TLS estrito, timeouts de conexão/leitura, cancelamento cooperativo, cabeçalho de autenticação desacoplado, zero logging de payload e mapeamento de HTTP `401`/`403`/`429`/`5xx`/timeout para `InferenceFailure`), com testes unitários via `MockWebServer` verificando contrato `exam-vision/1`, cancelamento e ausência de vazamento em logs (`./gradlew :inference-cloud:testDebugUnitTest`).
- [x] 4.2 Implementar preferências não-PII (`cloudFallbackOptIn`, `downloadOnWifiOnly`) e `HybridTextInference` em `:app`, integrando `ExamRunner` (aplicação da tarja de PII somente no caminho nuvem) e `ExamViewModel` (avaliação prévia pelo `InferenceRoutingDecisionEngine`, bloqueio/prompt de consentimento opt-in, retomada após autorização e recusa estritamente offline), com testes unitários verificando todas as transições de roteamento e `NoRestorableClinicalStateTest` (`./gradlew :app:testDebugUnitTest`).

## 5. UX de Consentimento Opt-in, Status de Download e Transparência (`:core-ui`, `:app`)

- [x] 5.1 Adicionar componentes e estados em `:core-ui` (`UiModels.kt`, `ProcessingScreen.kt`, `HelpScreen.kt`, `HomeScreen.kt`) para: (a) diálogo/folha de consentimento explícito `opt-in` informando tarja de PII em PDFs digitais, limitação em páginas escaneadas e zero retenção/treino; (b) cartão de gerenciamento dos pesos locais (progresso, baixar/pausar/remover) e chave para revogar fallback em nuvem; (c) indicador discreto do modo ativo (local vs nuvem privada) sem linguagem diagnóstica, verificando acessibilidade, contraste e alvos ≥ 48 dp em `:ui-checks` (`./gradlew :core-ui:test :ui-checks:compileDebugAndroidTestKotlin`).
- [x] 5.2 Atualizar `docs/LITERT_COMPATIBILITY.md` e textos de privacidade em `HelpScreen` refletindo a arquitetura Local-First + fallback opt-in e os critérios de hardware (≥ 6 GB RAM, GPU 64-bit), verificando aderência aos limites éticos de `AGENTS.md`.

## 6. Backend Stateless e Infraestrutura AWS (`backend/`)

- [x] 6.1 Criar especificação OpenAPI `backend/openapi/vision-fallback-v1.yaml` documentando `POST /v1/vision/extract`, autenticação, códigos de erro (`400`/`401`/`403`/`413`/`429`/`502`/`504`) e limites de payload (imagem ≤ 512 KiB, `exam-vision/1`), validando a sintaxe YAML/OpenAPI localmente.
- [x] 6.2 Implementar a função AWS Lambda proxy *stateless* em `backend/lambda/` (Python 3.12) validando protocolo/limites, invocando Amazon Bedrock Converse API (`google.gemma-4-e2b`, `temperature=0.0`, `maxTokens=1536`), sem gravação em disco/S3/DB e registrando apenas métricas técnicas sanitizadas (`requestId`, `statusCode`, `latencyMs`, `inputTokens`, `outputTokens`, `errorCode`), com testes unitários (`pytest`/`unittest`) comprovando rejeição de payloads inválidos, paridade de resposta e ausência de corpo/imagem nos logs.
- [x] 6.3 Criar IaC Terraform em `backend/infra/` provisionando API Gateway (TLS 1.2+, autorizador JWT configurável, throttling/rate limiting por cliente), AWS Lambda *stateless* (fora de VPC, custo fixo zero), política IAM e exemplo de SCP fixando a região permitida para `google.gemma-4-e2b` (sem *Cross-Region Inference*), *Model Invocation Logging* desativado e CloudWatch Log Group com retenção curta + alarme de orçamento, validando com `terraform fmt -check` e `terraform validate`.

## 7. Verificação Integrada End-to-End

- [x] 7.1 Executar suíte completa de testes (`./gradlew test`, testes Python de `backend/lambda/` e validação Terraform), validar APK leve padrão (sem pesos embarcados, `allowBackup="false"`, `usesCleartextTraffic="false"`, zero analytics) e validar build técnico `-PbundleLocalModel=true` no Galaxy S25 (`:ui-checks:connectedDebugAndroidTest` e regressão do caminho local offline).
