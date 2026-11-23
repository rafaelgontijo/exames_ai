# Design

## Context

Veja [proposal.md](proposal.md) para a motivação e evidências de benchmark no Mac e no Galaxy S25 offline.

Estado atual relevante da arquitetura:
- `:data-private-pdf` ([`PdfPreviewRenderer.kt`](../../../data-private-pdf/src/main/kotlin/br/com/exames/datadocuments/PdfPreviewRenderer.kt)) já produz, em uma única leitura de página em memória (`PdfPageSnapshot`), tanto o `bitmap: Bitmap` orientado na vertical pelo PDFium quanto `positionedText(): List<SourceFragment>` e `rawText: String`, sem arquivos temporários em texto claro no disco.
- `:inference-local` executa cada solicitação em um processo filho isolado (`:inference`, [`OneShotInferenceService.kt`](../../../inference-local/src/main/kotlin/br/com/exames/inferencelocal/OneShotInferenceService.kt) + [`ProcessInferenceClient.kt`](../../../inference-local/src/main/kotlin/br/com/exames/inferencelocal/ProcessInferenceClient.kt)) com `Engine` nova por solicitação (`cacheDir = ":nocache"`), `BundledModelStore` atômico com verificação SHA-256 em `noBackupFilesDir/models/gemma4.litertlm` e fronteira Java ([`FreshGemmaEngine.java`](../../../inference-local/src/main/java/br/com/exames/inferencelocal/FreshGemmaEngine.java)) para `com.google.ai.edge.litertlm:litertlm-android:0.18.1`.
- `:domain` é Kotlin/JVM puro (sem Android SDK), contendo `ExtractExam`, `ExamObservation`, `ConservativeExamAssessor` e `ExamSummaryProjection`.
- `EncryptedExamHistoryStore` persiste `ExamExtraction` serializado em JSON cifrado com AES-GCM + Android Keystore.

Restrições invioláveis (`AGENTS.md`):
- Sem permissão `INTERNET` nem dependências de rede; sem logs clínicos ou gravação de imagens/textos clínicos em texto claro no disco.
- `Engine` nova e processo `:inference` novo por solicitação; validar somente o canal final.
- Nunca afrouxar `NEEDS_REVIEW`, nunca inventar referências e nunca declarar validação clínica a partir de ensaios técnicos.
- Compatibilidade mínima mantida em `minSdk = 24`, `compileSdk = 36`, JDK 17/21.

## Goals / Non-Goals

**Goals:**
- Migrar o modelo padrão empacotado para `gemma-4-E2B-it.litertlm` (2,58 GB, rev `2e12bfd869b2962984f97a1f2a26807316d534cc`, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`, Apache-2.0) mantendo validação de integridade end-to-end (`selection.json`, `model.lock.json`, `stage_model.py`, `SelectedInferenceConfigurationTest`, `BundledModelStore`).
- Unificar a extração de páginas de PDF em um pipeline visual de faixas horizontais (sempre ativo para qualquer PDF, digital ou escaneado), removendo a dependência de cabeçalhos tabulares fixos.
- Implementar transporte seguro em memória de imagens de faixa via IPC `:inference` dentro de limites estritos de Binder (`minSdk 24`).
- Implementar ancoragem literal + resolução de regiões (`SourceRegion`) no `:domain` quando houver texto posicionado no PDF, filtrando alucinações e habilitando destaque no `Ver no laudo`.
- Tratar páginas escaneadas (sem texto digital) de forma segura, marcando todas as observações visuais como `NEEDS_REVIEW` com motivo documental explícito e preservando compatibilidade retroativa do histórico cifrado.

**Non-Goals:**
- Suporte a importação de fotos/imagens avulsas (PNG/JPG) pela galeria ou câmera (escopo restrito a PDFs).
- Reutilização de `Engine` ou processo `:inference` entre faixas (permanece `freshEnginePerRequest = true` e `freshProcessPerRequest = true` até eventual prova separada de reset seguro).
- Classificação clínica, conversão de unidades ou escolha de faixa condicional em nome do paciente.
- Validação clínica ou garantia de desempenho em aparelhos com 4 GB de RAM / GPUs não testadas.

## Decisions

### 1. Modelo padrão: `gemma-4-E2B-it.litertlm` (`GPU` + `visionBackend = GPU`, `audioBackend = null`)
- **Decisão:** Atualizar `validation/models/gemma4-e2b/model.lock.json`, `validation/models/selection.json`, `SelectedInferenceConfiguration` e `app/build.gradle.kts` para `gemma-4-E2B-it.litertlm` (`2.588.147.712` bytes, Apache-2.0), com `contextTokens = 4096`, `maxOutputTokens = 1536`, `maxNumImages = 1`, `backend = GPU`, `visionBackend = GPU` e `audioBackend = null`.
- **Justificativa:** No S25 offline, `audioBackend = null` evita alocar o encoder de áudio e manteve o PSS estável em ~2,96–3,01 GiB (apenas +~130 MiB sobre o baseline texto-only de 2,88 GiB), com `loadMs` ~8,7–12 s e `generationMs` ~2,2–7,1 s por faixa. Mantém licença Apache-2.0 e `litertlm-android:0.18.1` sem trocar runtime.
- **Alternativas descartadas:**
  - `LFM3-Omni-2B-LiteRT` (3,23 GB, LFM Open License v1.0): reprovado na Etapa 1 (23/36 no Track A, 0/13 ancorados em visão página inteira e 21 alucinações vs 12 ancorados em 3 faixas, ~1,8× mais lento, licença comercial restritiva).
  - Manter `gemma-4-E2B-it-gpu.litertlm` (2,01 GB): não contém `tf_lite_vision_encoder`.

### 2. Extração visual em 3 faixas horizontais sobrepostas + descarte determinístico de faixas vazias
- **Decisão:** Em `:data-private-pdf` (`PdfPageBandSlicer`), fatiar o `Bitmap` orientado da página em até 3 faixas horizontais sobrepostas (`[0.00..0.42]`, `[0.32..0.74]`, `[0.62..1.00]`, mantendo a caixa normalizada `SourceRegion` da faixa na página). Antes de codificar/enviar uma faixa para a IA, aplicar um filtro determinístico de conteúdo visual (proporção de pixels não-brancos/escuros em grade amostrada abaixo de um limiar conservador, ex.: `< 0.15%` de pixels de tinta) para pular faixas em branco.
- **Justificativa:**
  - No benchmark S25, 3 faixas horizontais atingiram 69/72 resultados ancorados (incluindo 26/26 na p14 do `pdf_2` e 17/17 na p1 do `pdf_1`) contra falhas de truncamento e perda de resolução em página A4 inteira.
  - As únicas 3 alucinações no S25 ocorreram em faixas vazias/rodapé de páginas simples; pular faixas sem tinta elimina chamadas de ~12 s desperdiçadas e corta na raiz a fonte das alucinações em faixas vazias.
- **Alternativas descartadas:**
  - Página inteira única: perde legibilidade em tabelas densas e truncou JSON em páginas de 20+ itens.
  - Pipeline híbrido (texto para layout conhecido + visão para desconhecido): o usuário optou explicitamente por unificar 100% das páginas em visão em faixas para máxima generalização entre formatos desconhecidos, preservando o texto digital como camada de validação e ancoragem de caixas.

### 3. Protocolo visual com schema neutro (`exam-vision/1` + `exam-vision-items/1`)
- **Decisão:** Criar `VisionExamProtocol` em `:domain` com prompt em inglês conciso usando placeholders estruturais neutros (`<exact printed test name>`, `<exact printed value>`) — **nunca** nomes/valores clínicos concretos como `"Hemoglobina": "14,2"` — instruindo JSON compacto numa única linha:
  `{"schema":"exam-vision-items/1","items":[{"name":"...","result":"...","unit":"..."|null,"reference":"..."|null,"needs_review":false}]}`
  Validar estritamente `ProposalProtocol.readStrictJson` (sem markdown fences, limite `MAX_FINAL_CHARACTERS = 16384`, máximo de itens por faixa limitado a 64).
- **Justificativa:** Remove o viés de eco do exemplo concreto observado no benchmark (onde modelos copiavam `"Hemoglobina": "14,2"` em faixas com pouco texto) e mantém o mesmo padrão de validação estrita de schema sem reparo de JSON exigido pela governança.

### 4. Transporte IPC em memória para imagem da faixa (`minSdk 24`, zero disco)
- **Decisão:** Estender `GenerationRequest` (ou introduzir `imageBytes: ByteArray? = null` + `imageMimeType: String? = null` preservando `TextInference` compatível no `:domain`) e `InferencePayloadLimits` com `MAX_IMAGE_BYTES = 512 * 1024` (512 KiB). `PdfPageBandSlicer` redimensiona a faixa se `width > 1240` px e codifica em PNG; se o PNG exceder `512 KiB` (ex.: PDF escaneado fotográfico colorido), recodifica em JPEG qualidade 88% (e 75% se necessário), rejeitando com `INPUT_LIMIT` apenas se ainda ultrapassar 512 KiB. `InferenceProcessProtocol` transporta `imageBytes` no `Bundle` da mensagem `RUN` diretamente em memória para `OneShotInferenceService` → `FreshGemmaEngine.generateVision(...)` usando `Content.ImageBytes(imageBytes)`.
- **Justificativa:** Em ensaios reais, a média por faixa PNG a 150 DPI foi ~108 KiB (4,2 MiB para 39 faixas), muito abaixo do limite de transação do Binder (~1 MiB). Mantém zero arquivos temporários clínicos no disco, compatibilidade com API 24 e isolamento total do processo `:inference` (incluindo kill em timeout/cancelamento).
- **Alternativas descartadas:**
  - Gravar PNG em arquivo temporário e passar `Content.ImageFile`: viola o princípio de evitar derivados clínicos em texto claro no disco.
  - `SharedMemory` (API 27+): quebraria `minSdk = 24`.

### 5. Ancoragem cruzada com texto posicionado e fallback `NEEDS_REVIEW` (`VisionEvidenceGrounder`)
- **Decisão:** Em `:domain` (Kotlin/JVM puro), criar `VisionEvidenceGrounder` que recebe os itens deduplicados das faixas de uma página + `List<SourceFragment>` da mesma página:
  - **Caso A — Página com texto digital (`fragments` não vazio com caracteres alfanuméricos):**
    1. Reconstrói linhas ordenadas (`top`, `left`) a partir dos `SourceFragment`s.
    2. Para cada item proposto pela visão, exige **ancoragem literal do `result`** (normalização Unicode NFD/espaços sem alterar dígitos/sinais) nos fragmentos da página, preferindo fragmentos dentro da janela vertical da faixa de origem (`bandRegion`) e próximos às palavras do `name`.
    3. Se o `result` **não** existir literalmente na página → **filtrar o item** (alucinação bloqueada) e adicionar `ExamIssue.EVIDENCE_NEEDS_REVIEW` na página.
    4. Quando ancorado: resolver as `SourceRegion`s dos fragmentos casados para `name`, `sourceResult` (e `unitCitation` se adjacente) e `reference` (exigindo que os números/tokens da referência apareçam nos fragmentos vizinhos/da página; se a referência proposta não ancorar ou tiver `needs_review = true`, marcar `referenceNeedsReview = true` e não publicar referência não verificada).
    5. Deduplicar observações entre faixas sobrepostas priorizando itens ancorados com referência verificada e, em caso de valores divergentes para a mesma âncora/nome na mesma região, sinalizar `EVIDENCE_NEEDS_REVIEW`.
  - **Caso B — Página escaneada (`fragments` vazio ou sem texto digital legível):**
    1. Preservar todos os itens bem-formados propostos pela visão, com `visualOnly = true`, `referenceNeedsReview = true` (ou flag dedicada `visualUnanchored = true` com valor padrão `false` para retrocompatibilidade JSON!), e `regions = listOf(bandRegion)` (ou lista vazia com `page` preservado) para abrir a página/faixa correspondente em `Ver no laudo`.
    2. Adicionar `ExamIssue.SCANNED_PAGE_VISUAL_ONLY` (ou reutilizar `INSUFFICIENT_POSITIONED_TEXT`) na página para exibir aviso claro de que a página não possui texto digital.
    3. Em `ConservativeExamAssessor`, se `observation.visualOnly` for `true`, retornar **sempre** `DocumentaryStatus.NEEDS_REVIEW` com `DocumentaryReason.VISUAL_EXTRACTION_WITHOUT_DIGITAL_TEXT` (nunca `WITHIN_RANGE`), independentemente de o valor parecer estar dentro da faixa.

### 6. Retrocompatibilidade do histórico cifrado e da UI
- **Decisão:**
  - Todos os novos campos em `@Serializable` (`ExamObservation.visualOnly: Boolean = false`, `ExamObservation.page: Int? = null`, `ExamCitation.regions` podendo ser vazia ou conter região de faixa) terão **valores padrão**, garantindo que `EncryptedExamHistoryStore` desserialize exames salvos pela versão anterior sem falha.
  - Em `ExamUiMapper.toEvidenceUi`, determinar `page` com fallback `resultRegions.firstOrNull()?.page ?: referenceRegions.firstOrNull()?.page ?: row.page ?: 1`, para que `Ver no laudo` sempre abra a página correta mesmo sem caixas por palavra em PDFs escaneados.
  - Em `ExamRunner`, atualizar `MAX_DOCUMENT_REQUESTS` em `ExtractExam` de `64` (dimensionado para blocos pequenos) para `64` ou `60` (`20 páginas × 3 faixas = 60` solicitações máximas — já cabe dentro de `64`!).

## Risks / Trade-offs

- **[Latência por página (~35–55 s em 3 faixas no S25)]** → Pular faixas vazias reduz páginas simples para 1–2 faixas (~15–30 s); manter barra de progresso granular por página e faixa (`page`, `block`, `blocks`) e cancelamento imediato já existentes em `ProcessingScreen`.
- **[Regressão de cobertura em tabelas muito densas como `pdf_1` p1 (14/21 exatos no benchmark inicial de visão vs 21/21 em texto)]** → O prompt de schema neutro com instrução explícita para extrair todas as linhas da faixa + ajuste fino de sobreposição de faixas (ex.: 40% por faixa) será medido no S25 offline sobre `pdf_1`, `pdf_2` e `pdf_3` antes de fechar o aceite; qualquer omissão ou ambiguidade permanece visível por página e sem alucinações silenciosas.
- **[Aumento de +580 MB no APK padrão (`2,01 GB` → `2,58 GB`)]** → Mantém empacotamento `noCompress += "litertlm"` e verificação de espaço livre (`spec.size + 64 MiB`) em `BundledModelStore`; em aparelhos sem espaço, falha com `InferenceFailure.MODEL_SPACE` de forma explícita.
- **[PDFs escaneados em alta resolução excedendo 512 KiB por faixa PNG]** → `PdfPageBandSlicer` limita a largura da faixa a 1240 px e aplica fallback determinístico para JPEG 88%/75% antes do envio via Binder, mantendo transporte 100% em memória.
- **[Compatibilidade de GPU fora do S25]** → `SelectedInferenceConfiguration.referenceDeviceCompatible()` continua restrito ao dispositivo de referência validado (`SM-S931B`, arm64-v8a, API >= 24) sem declarar suporte a GPUs não testadas.

## Migration Plan

1. Atualizar lock/seleção do modelo (`validation/models/gemma4-e2b/model.lock.json`, `validation/models/selection.json`), `tools/litert/stage_model.py` (se necessário validar `visionBackend`) e `app/build.gradle.kts` para apontar para o artefato `gemma-4-E2B-it.litertlm` em `../.model-cache/gemma4-e2b-mm/gemma-4-E2B-it.litertlm`.
2. Atualizar `:domain` (protocolo visual, grounder de evidência, assessor conservador, `ExtractExam`) com cobertura completa de testes unitários de limites e fallback.
3. Atualizar `:inference-local` (configuração selecionada, IPC com `imageBytes` limitado, `FreshGemmaEngine` multimodal GPU+Visão GPU) e remover o spike temporário `GemmaMultimodalVisionSpikeTest.java`.
4. Atualizar `:data-private-pdf` (`PdfPageBandSlicer`) e `:app` (`ExamRunner`, `ExamUiMapper`, testes).
5. Validar build (`./gradlew assembleDebug`), testes unitários (`./gradlew test`), testes instrumentados no S25 e ensaio offline autorizado no S25 com os PDFs reais (registrando apenas contagens agregadas em `validation/results/`, sem PII).
6. **Rollback:** Como o esquema de persistência cifrada é retrocompatível (somente campos opcionais com valor padrão), reverter a branch restaura integralmente `gemma-4-E2B-it-gpu.litertlm` e o pipeline anterior sem perda de dados locais.
