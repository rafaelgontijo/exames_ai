# Design

## Context

Veja [proposal.md](proposal.md) para a motivação e a mudança de governança de 100% offline com APK de 2,58 GB para arquitetura **Local-First + Fallback Privado na Nuvem (`opt-in`)** com download sob demanda dos pesos.

Estado atual observado no repositório:
- `:domain` define `TextInference`, `GenerationRequest` (com `imageBytes` e `imageMimeType` opcionais em memória), `GenerationResult`, `InferenceFailure` e `VisionExamProtocol` (`exam-vision/1`, `exam-vision-items/1`).
- `:inference-local` implementa `LocalTextInference` usando `ProcessInferenceClient` → `InferenceProcessService` (`:inference`) → `FreshGemmaEngine` (LiteRT-LM `0.18.1`, GPU + visão GPU, `Engine` e processo novos por faixa) e `BundledModelStore` (que copia `assets/models/gemma4.litertlm` de forma atômica para `noBackupFilesDir/models/gemma4.litertlm` com validação SHA-256).
- `:app` acopla `stageBundledModel` em `app/build.gradle.kts` (embutindo 2,58 GB no APK) e remove `INTERNET` / `ACCESS_NETWORK_STATE` via `tools:node="remove"` em `AndroidManifest.xml`.
- `:data-private-pdf` já produz, em `ExamRunner`, tanto o `Bitmap` renderizado por página (`PdfPageBandSlicer`) quanto as palavras posicionadas (`PdfWordFragments` / `SourceFragment` com `SourceRegion` em coordenadas de pixel da página).
- Não existe hoje backend/IaC no repositório nem preferência persistida de consentimento ou download.

Restrições invariantes preservadas:
- `:domain` permanece Kotlin/JVM puro sem Android SDK e sem bibliotecas de rede.
- Limites clínicos/éticos de `AGENTS.md` e `document-evidence-grounding` permanecem intactos (`NEEDS_REVIEW`, referências brutas, sem diagnóstico, contexto clínico estritamente em RAM).
- Zero telemetria, zero crash reports com conteúdo clínico, `allowBackup="false"`, `usesCleartextTraffic="false"`, histórico cifrado em Keystore e zero gravação de imagens de faixas em disco.

## Goals / Non-Goals

**Goals:**
- Unificar a execução local e em nuvem atrás da interface de domínio `TextInference` (com roteador `HybridTextInference`), mantendo prompt (`exam-vision/1`), schema (`exam-vision-items/1`), grounding e `ConservativeExamAssessor` 100% idênticos.
- Reduzir o tamanho do APK removendo os 2,58 GB embutidos e migrando a entrega dos pesos `gemma-4-E2B-it.litertlm` para download sob demanda verificado por tamanho + SHA-256 em `noBackupFilesDir`.
- Implementar motor de decisão determinístico em `:domain` (RAM, GPU/ABI, estado dos pesos, consentimento `opt-in`) com cobertura unitária de limites e fallback.
- Aplicar tarja visual opaca local de PII sobre o bitmap da faixa em memória (usando coordenadas do `PdfWordFragments` em PDFs digitais) antes de qualquer envio ao fallback em nuvem.
- Entregar no mesmo repositório (`backend/`) o contrato OpenAPI, a AWS Lambda proxy *stateless* (sem persistência, logs sanitizados, rate limiting + auth) e IaC Terraform provisionando API Gateway (TLS 1.2+) e invocação regional do Amazon Bedrock (`google.gemma-4-e2b`) sem custo fixo de VPC/PrivateLink.

**Non-Goals:**
- Suporte a clientes iOS ou Web neste repositório (esta change cobre o app Android nativo e o backend AWS compartilhável via contrato OpenAPI).
- Modo *Batch/Flex* assíncrono do Bedrock ou filas SQS (o app exige resposta síncrona por faixa para acompanhar progresso e cancelamento interativo).
- Remoção do caminho local ou degradação do isolamento de processo `:inference` / `Engine` novo por faixa.
- Armazenamento de exames ou perfis de usuário na nuvem (o backend é 100% *stateless*; histórico continua exclusivamente local e cifrado no aparelho).
- Validação clínica automatizada (testes cobrem contratos técnicos, privacidade, segurança de rede e integridade documental).

## Decisions

### 1. Arquitetura de módulos e abstração `TextInference`
- **Decisão:** Reaproveitar `br.com.exames.domain.inference.TextInference` como contrato unificado (evitando duplicar abstrações), criando:
  - `:domain`: `InferenceRoutingDecisionEngine`, `DeviceCapabilitySnapshot`, `LocalWeightsState`, `CloudFallbackPolicy`, `InferenceExecutionTarget` (`LOCAL`, `CLOUD_FALLBACK`, `BLOCKED_CONSENT_REQUIRED`, `BLOCKED_OFFLINE`), `PiiRegionDetector` (puro JVM sobre `List<SourceFragment>`), e novos códigos em `InferenceFailure` (`CLOUD_CONSENT_REQUIRED`, `NETWORK_UNAVAILABLE`, `CLOUD_UNAUTHORIZED`, `CLOUD_RATE_LIMITED`, `CLOUD_UNAVAILABLE`).
  - `:inference-local`: evoluir `BundledModelStore` para `LocalModelStore` (mantendo `AtomicModelPreparer` + suporte opcional a build técnico com pesos locais via flag Gradle `-PbundleLocalModel=true` para ensaios offline no S25), adicionar `OnDemandModelDownloader` (download HTTPS em background via coroutines/`WorkManager` com retomada/cancelamento e validação SHA-256) e `AndroidDeviceCapabilityProbe` (`ActivityManager.MemoryInfo` para RAM total/disponível, `Process.is64Bit()`, suporte Vulkan/OpenGL ES 3.1+).
  - Novo módulo `:inference-cloud`: `CloudFallbackInference : TextInference` usando cliente HTTPS minimalista (`OkHttp` com cache de disco explicitamente desabilitado `cache(null)`, TLS 1.2+, timeouts estritos, cancelamento cooperativo vinculado à coroutine e interceptador sem logging de payload).
  - `:app`: `HybridTextInference` orquestrando `InferenceRoutingDecisionEngine` → `LocalTextInference` ou `CloudFallbackInference`, conectado a preferências não-PII em DataStore/SharedPreferences privadas (`cloudFallbackOptIn`, `downloadOnWifiOnly`).
- **Alternativas consideradas:**
  - *Criar uma segunda interface `InferenceProvider` paralela a `TextInference`*: descartada por duplicar contratos em `ExtractExam` e `ExamRunner` sem ganho funcional.
  - *Usar `HttpURLConnection` puro em vez de OkHttp*: OkHttp oferece cancelamento cooperativo limpo (`Call.cancel()`), controle explícito de `cache(null)`, connection specs TLS estritos e timeouts granulares (`connect`/`read`/`call`), além de testes determinísticos com `MockWebServer`; como a governança muda para arquitetura híbrida, OkHttp entra isolado apenas em `:inference-cloud` e `:inference-local` (nunca em `:domain`).

### 2. Critérios do Decision Engine e fluxo `opt-in`
- **Decisão:**
  - Limiares padrão em `:domain` (`DeviceCapabilityThresholds`): `MIN_TOTAL_RAM_BYTES = 6 GiB` (baseado na medição real no S25 de ~3,04 GiB de PSS do Gemma 4 E2B multimodal + margem do SO), `MIN_AVAILABLE_RAM_BYTES = 2.5 GiB`, `is64Bit = true`, `hasSupportedGpu = true`.
  - Fluxo de roteamento antes de iniciar a extração em `ExamViewModel`:
    1. Se `hardwareCapable && weightsState == READY` → `LOCAL` (zero rede).
    2. Se `cloudFallbackOptIn == true` e `(hardwareIncapable || weightsState != READY)` → `CLOUD_FALLBACK` (exibindo selo discreto de nuvem privada durante processamento).
    3. Se `cloudFallbackOptIn == false` e `(hardwareIncapable || weightsState != READY)` → exibe diálogo/folha de decisão **opt-in explícito** explicando o motivo (hardware limitado ou pesos ainda baixando), o mecanismo de privacidade (tarja local de PII em PDFs digitais, TLS, zero retenção/treino, sem tarja automática em páginas escaneadas) e oferecendo: **"Autorizar nuvem privada"** ou **"Continuar apenas offline"** (com atalho para baixar/aguardar pesos locais quando o hardware for elegível).
- **Alternativas consideradas:**
  - *Fallback silencioso automático*: rejeitado pelo usuário (decisão A1) para preservar confiança e transparência de privacidade em dados de saúde (LGPD).
  - *4 GB RAM mínimo*: arriscado porque o PSS medido em GPU com Gemma 4 E2B multimodal atingiu `3,04 GiB` em `SM-S931B`; 6 GB total + 2,5 GB disponíveis reduz risco de `LMK`/`PROCESS_DIED`.

### 3. Tarja visual local de PII em memória (`:domain` + `:data-private-pdf`)
- **Decisão:**
  - Em `:domain`, `PiiRegionDetector.detect(fragments: List<SourceFragment>): List<SourceRegion>` identifica caixas de redação puramente determinísticas:
    - Regex explícitas em tokens/linhas: CPF (`\d{3}\.?\d{3}\.?\d{3}-?\d{2}`), e-mail, telefone brasileiro (`(DD) 9xxxx-xxxx` / `+55`), RG/CNS/carteirinha quando precedidos de rótulo.
    - Linhas de cabeçalho administrativo/identificação do paciente: linhas contendo rótulos como `Paciente:`, `Nome:`, `CPF:`, `DN:`/`Data de nascimento:`, `Mãe:`, `Convênio:`, `Prontuário:`, `Atendimento:`, `Médico solicitante:` — retornando as `SourceRegion` dos valores à direita do rótulo (e preservando linhas que contenham medições/resultados laboratoriais).
  - Em `:data-private-pdf`, `PdfPageBandSlicer.slice(page, bitmap, redactionRegions)` (sobrecarga retrocompatível com `redactionRegions = emptyList()` no caminho local) cria uma cópia mutável em RAM do recorte da faixa apenas quando `target == CLOUD_FALLBACK`, projeta cada `SourceRegion` para coordenadas da faixa (com pequena margem de 2 px) e pinta retângulos opacos neutros antes de comprimir PNG/JPEG ≤ 512 KiB.
  - O bitmap original e o PDF cifrado local nunca são alterados; a tarja existe apenas no buffer efêmero enviado à nuvem.
- **Alternativas consideradas:**
  - *Enviar apenas texto mascarado em PDFs digitais*: rejeitado pelo usuário (decisão A4) para manter paridade total de prompt/visão com a extração local por faixas.
  - *OCR local para redigir páginas escaneadas*: fora de escopo (adicionaria peso/latência sem garantia); em vez disso, o consentimento deixa explícito que páginas escaneadas sem texto digital não possuem tarja automática baseada em texto.

### 4. Entrega sob demanda dos pesos locais (`:inference-local`)
- **Decisão:**
  - Remover a obrigatoriedade do `stageBundledModel` no build padrão (`app/build.gradle.kts`), mantendo-o apenas atrás da flag opcional `-PbundleLocalModel=true` para builds de validação offline em bancada/S25.
  - Manter a fonte de verdade de versão/revisão/tamanho/SHA-256 em `validation/models/gemma4-e2b/model.lock.json` e `SelectedInferenceConfiguration`, adicionando a URL HTTPS oficial/espelhada configurável via `BuildConfig` para o download sob demanda.
  - Reaproveitar `AtomicModelPreparer` (lock por arquivo `.prepare.lock`, escrita em `.gemma4-<uuid>.partial`, hash SHA-256 incremental em streaming de 1 MiB, `fsync` + `Os.rename` atômico).
  - Orquestrar o download em background via `WorkManager` (com `Constraints(NetworkType.UNMETERED)` por padrão quando `downloadOnWifiOnly = true`, notificação de progresso do SO opcional e cancelamento limpo).
- **Alternativas consideradas:**
  - *Google Play Asset Delivery (`play-asset-delivery`)*: acopla fortemente à Play Store e dificulta distribuição/teste por sideload; download HTTPS verificado por SHA-256 funciona de forma agnóstica em qualquer canal e reaproveita integralmente `AtomicModelPreparer`.

### 5. Backend AWS *Stateless* + IaC (`backend/`)
- **Decisão:**
  - Estrutura em `backend/`:
    - `backend/openapi/vision-fallback-v1.yaml`: contrato HTTP `POST /v1/vision/extract` recebendo JSON compactado (`promptVersion`, `schemaVersion`, `imageMimeType`, `imageBase64` limitado a ≤ 512 KiB decodificados, `instruction`/`documentText` validados contra o conjunto permitido do protocolo `exam-vision/1`) e retornando `{ "text": "...", "usage": { "inputTokens": ..., "outputTokens": ... }, "latencyMs": ... }`.
    - `backend/lambda/`: função Python 3.12 (ou TypeScript/Node 22 — preferir **Python 3.12** pela leveza de cold start, zero dependências além de `boto3` e fácil auditoria) estritamente *stateless*:
      - Valida tamanho de payload, MIME (`image/png`, `image/jpeg`) e versão do protocolo (`exam-vision/1`).
      - Invoca **Amazon Bedrock Converse API** (`modelId = "google.gemma-4-e2b"`, `temperature = 0.0`, `maxTokens = 1536`, imagem em bytes + texto do prompt).
      - Nunca persiste em S3/DynamoDB/disco (`/tmp`), nunca registra `event["body"]`, imagem ou resposta em `stdout`/CloudWatch — emite apenas JSON estruturado com `requestId`, `statusCode`, `latencyMs`, `inputTokens`, `outputTokens` e `errorCode`.
    - `backend/infra/` (**Terraform** — padrão declarativo multi-ambiente com validação via `terraform validate` e política explícita):
      - **API Gateway (HTTP API v2 ou REST API com Usage Plan / throttling)** com TLS 1.2+, autorizador JWT/API token por cliente (ex.: Cognito App Attest / JWT assinado + *rate limiting* por `sub`/chave).
      - **Lambda fora de VPC (custo fixo de rede US$ 0/mês):** invoca o endpoint regional `bedrock-runtime.<fixed-region>.amazonaws.com` sobre TLS 1.2+/1.3 + assinatura SigV4 diretamente no backbone AWS, evitando o custo fixo de ~US$ 15–22/mês de VPC Endpoints / PrivateLink e reduzindo latência de cold start.
      - **IAM Policy + exemplo de SCP regional:** restringe `bedrock:InvokeModel` ao ARN exato `arn:aws:bedrock:<fixed-region>::foundation-model/google.gemma-4-e2b` na região fixa configurada (bloqueando *Cross-Region Inference Profiles* fora da jurisdição autorizada).
      - **Bedrock Model Invocation Logging:** explicitamente mantido desativado (e verificado em checklist/teste de infraestrutura).
      - **CloudWatch Log Group** com retenção curta (ex.: 14 dias) e filtro/teste garantindo ausência de campos de payload.
- **Alternativas consideradas:**
  - *VPC dedicada + AWS PrivateLink (Interface VPC Endpoint)*: descartada para manter custo fixo zero (US$ 15–22/mês em 2–3 AZs superaria o próprio custo mensal projetado do Bedrock), já que o tráfego regional Lambda → Bedrock já usa TLS 1.2+/1.3 + SigV4 no backbone AWS e os controles centrais de privacidade (zero persistência, logs sanitizados, *Model Invocation Logging* off, região fixa por IAM) não dependem de VPC.
  - *ECS Fargate em vez de AWS Lambda*: custo fixo 24/7 incompatível com a projeção de US$ 6–24/mês para cauda longa; Lambda escala a zero.
  - *Enviar PDF inteiro ao backend*: violaria o princípio de minimização de dados e quebraria a paridade de faixas com tarja visual local de PII.

## Risks / Trade-offs

- **[Mudança de contrato de privacidade (zero-INTERNET → híbrido opt-in)]** → Mitigação: consentimento explícito obrigatório antes de qualquer envio de exame, modo estritamente offline preservado na recusa, tarja local de PII em PDFs digitais, `usesCleartextTraffic="false"`, zero cache HTTP em disco e atualização transparente de `AGENTS.md` e textos da tela de Ajuda/Privacidade.
- **[PII em páginas escaneadas sem texto digital não pode ser tarjado por coordenadas de texto]** → Mitigação: o diálogo de consentimento informa explicitamente essa limitação antes de enviar; o usuário pode recusar e aguardar o modelo local se o hardware suportar.
- **[Falso positivo na tarja de PII encobrindo um exame próximo ao cabeçalho]** → Mitigação: restringir a tarja a padrões explícitos de PII (CPF, e-mail, telefone) e valores à direita de rótulos administrativos de identificação (nunca linhas que contenham unidade/valor laboratorial); cobrir com testes unitários em `:domain` e `:data-private-pdf`.
- **[Divergência de formatação JSON entre LiteRT-LM local e Bedrock `google.gemma-4-e2b`]** → Mitigação: ambos usam exatamente o mesmo prompt `exam-vision/1`, `temperature = 0.0`, `maxTokens = 1536`, e a saída da nuvem passa pelo mesmo `VisionExamProtocol.decode` + `VisionEvidenceGrounder` + `ConservativeExamAssessor`.
- **[Abuso / custo inesperado no endpoint de fallback]** → Mitigação: autenticação obrigatória por usuário/dispositivo + throttling/rate limiting no API Gateway + teto estrito de bytes por imagem (512 KiB) e `maxTokens = 1536` validados na Lambda + alarme de orçamento/CloudWatch.
- **[Interrupção de download de 2,58 GB em rede móvel ou falta de espaço]** → Mitigação: checagem prévia de espaço (`MODEL_BYTES + 64 MiB`), restrição opcional a Wi-Fi (padrão ativo), gravação em `.partial` com `Os.rename` atômico e verificação SHA-256 obrigatória.
- **[Disponibilidade regional do modelo `google.gemma-4-e2b` no Amazon Bedrock]** → Mitigação: parametrizar `aws_region` na IaC com validação explícita de jurisdição permitida e política IAM bloqueando perfis *cross-region* não autorizados.

## Migration Plan

1. **Governança e módulos base:** Atualizar `AGENTS.md` e `settings.gradle.kts` (adicionar `:inference-cloud`), habilitar `INTERNET`/`ACCESS_NETWORK_STATE` mantendo `usesCleartextTraffic="false"` e `allowBackup="false"`, e transformar `stageBundledModel` em opt-in de bancada (`-PbundleLocalModel=true`).
2. **Domínio puro (`:domain`):** Implementar `InferenceRoutingDecisionEngine`, `PiiRegionDetector` e novos `InferenceFailure` com suíte completa de testes unitários de limites e fallback.
3. **Redação e entrega de pesos (`:data-private-pdf`, `:inference-local`):** Adicionar redação visual em RAM em `PdfPageBandSlicer`, `AndroidDeviceCapabilityProbe` e `OnDemandModelDownloader` com verificação SHA-256 atômica.
4. **Cliente nuvem e UI (`:inference-cloud`, `:core-ui`, `:app`):** Implementar `CloudFallbackInference`, `HybridTextInference`, preferências sem PII, fluxo `opt-in` de consentimento, status de download de pesos e indicador discreto de modo de execução.
5. **Backend AWS (`backend/`):** Implementar OpenAPI, Lambda proxy *stateless* com testes unitários locais (mock do Bedrock verificando zero persistência e logs sanitizados) e IaC Terraform (`terraform fmt`/`validate`) para API Gateway, Lambda fora de VPC, políticas IAM/SCP regionais do Bedrock e alarmes de custo/rate-limit.
6. **Verificação e rollback:** Manter todos os testes JVM, `:ui-checks` e ensaios offline em S25 (usando `-PbundleLocalModel=true` quando testar sem rede). Em caso de regressão no fallback em nuvem, uma chave de configuração/feature flag no cliente e a revogação da rota no API Gateway permitem retorno imediato ao modo puramente local sem impacto ao histórico cifrado no aparelho.

## Open Questions

- **Provedor de autenticação do cliente móvel no API Gateway:** A IaC pode iniciar com autorizador JWT configurável (ex.: Cognito/OIDC + Play Integrity / App Attest futuro) mantendo a interface de token desacoplada na camada `:inference-cloud`; a escolha do IdP final de produção pode ser fixada no momento do deploy AWS sem alterar as specs nem o contrato HTTP.
- **URL de hospedagem final dos pesos `gemma-4-E2B-it.litertlm`:** O cliente e a validação SHA-256 dependem apenas de uma URL HTTPS configurável por `BuildConfig` (ex.: CloudFront/bucket público somente leitura ou mirror oficial Hugging Face); a definição do domínio final pode ser injetada na configuração de release.
