# Proposal

## Why

O pipeline atual de extração em `:domain` ([`ExamEvidence.kt`](../../../domain/src/main/kotlin/br/com/exames/domain/extraction/ExamEvidence.kt)) depende de um detector de cabeçalho tabular (`Exame ... Resultado ... Referência`) e usa o artefato somente-texto do Gemma 4 E2B (`gemma-4-E2B-it-gpu.litertlm`, 2,01 GB). Em ensaios locais com três laudos reais de formatos distintos, o pipeline atual extraiu 36/36 registros no laudo tabular conhecido, mas retornou 0 resultados nos outros dois laudos (16 páginas de laboratórios com layout em blocos) e não consegue processar PDFs escaneados sem camada de texto.

Ensaios comparativos no Mac e no Galaxy S25 offline com `LiteRT-LM 0.18.1` demonstraram que o artefato multimodal oficial **`gemma-4-E2B-it.litertlm`** (2,58 GB, mesma licença Apache-2.0, `backend=GPU` + `visionBackend=GPU`) opera com estabilidade de memória (~3,01 GiB PSS vs 2,88 GiB atual, ~5,6–6,2 GB livres no S25) e extrai resultados diretamente das imagens das páginas fatiadas em faixas horizontais em layouts nunca vistos (69/72 resultados ancorados no texto dos 3 PDFs no S25, incluindo 26/26 na página mais densa do corpus).

## What Changes

- **BREAKING (artefato de modelo empacotado):** Atualizar o modelo padrão selecionado em `validation/models/selection.json`, `validation/models/gemma4-e2b/model.lock.json`, `SelectedInferenceConfiguration` e `app/build.gradle.kts` de `gemma-4-E2B-it-gpu.litertlm` (2,01 GB, rev `1caa527c`, somente texto) para **`gemma-4-E2B-it.litertlm`** (`2.588.147.712` bytes / ~2,58 GB, rev `2e12bfd869b2962984f97a1f2a26807316d534cc`, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`, Apache-2.0), com `backend = GPU`, `visionBackend = GPU`, `audioBackend = null`, `contextTokens = 4096` e `maxOutputTokens = 1536`.
- **Pipeline unificado de extração visual em faixas (sempre ativo para todas as páginas de PDF):**
  - Substituir a extração dependente de cabeçalho tabular (`ExamEvidence` + `exam-extraction/4`) por um pipeline visual unificado em faixas horizontais sobrepostas por página (até 3 faixas, pulando faixas em branco/rodapé sem conteúdo útil) executado com `Engine` e processo `:inference` novos por faixa.
  - Adotar protocolo de visão com prompt de schema neutro (`exam-vision/1` + `exam-vision-items/1`, sem exemplos clínicos literais que possam ser ecoados em faixas vazias), validação JSON estrita (somente canal final) e deduplicação conservadora entre faixas sobrepostas.
- **Validação cruzada com texto digital e suporte a PDFs escaneados:**
  - Manter o fluxo de entrada exclusivo para **PDFs** (`Selecionar PDF`, agora aceitando PDFs digitais de qualquer layout e PDFs escaneados sem texto digital).
  - Quando a página do PDF possuir texto posicionado (`SourceFragment`s), cruzar cada item proposto pela visão com o texto literal da página para filtrar resultados não ancorados (guarda anti-alucinação) e resolver as caixas (`SourceRegion`) de nome, resultado, unidade e referência para destaque no `Ver no laudo`.
  - Quando a página for escaneada (sem texto digital), preservar os resultados propostos pela visão sempre marcados como `NEEDS_REVIEW` com motivo documental explícito (`VISUAL_EXTRACTION_WITHOUT_DIGITAL_TEXT`), apontando `Ver no laudo` para a página/faixa de origem sem caixas por campo.
- **Limpeza de artefatos exploratórios:** Remover ou incorporar de forma estruturada o teste exploratório temporário `GemmaMultimodalVisionSpikeTest.java` criado durante a triagem.

## Capabilities

### New Capabilities
- `multimodal-vision-extraction`: Extração local offline de resultados laboratoriais a partir de faixas horizontais renderizadas de páginas de PDF usando Gemma 4 E2B multimodal (`LiteRT-LM 0.18.1`, GPU + Visão GPU, processo `:inference` isolado por faixa), com prompt de schema neutro, detecção de faixas vazias, deduplicação entre faixas e protocolo de IPC limitado em memória.
- `document-evidence-grounding`: Ancoragem e validação conservadora de observações extraídas por visão contra o texto posicionado do PDF quando disponível (filtrando alucinações e resolvendo regiões de destaque para `Ver no laudo`) e fallback explícito `NEEDS_REVIEW` em páginas escaneadas ou sem ancoragem literal inequívoca.

### Modified Capabilities
<!-- Nenhuma spec principal existente sob openspec/specs/ é modificada neste repositório. -->

## Impact

- **Modelos e build:** `validation/models/selection.json`, `validation/models/gemma4-e2b/model.lock.json` (e atualização de `UPSTREAM_README.md`/hash de licença se necessário), `tools/litert/stage_model.py`, `app/build.gradle.kts` (+~580 MB no APK padrão).
- **`:domain` (Kotlin/JVM puro):** Contratos de inferência multimodal (`VisionGenerationRequest` / suporte a imagem limitada em `GenerationRequest`), `VisionExamProtocol` (`exam-vision/1`, `exam-vision-items/1`), `VisionEvidenceGrounder` (ancoragem literal e resolução de `SourceRegion`s a partir de `SourceFragment`s), atualização de `ExtractExam`, `ExamObservation` (suporte a origem visual / `unverifiedVisual` sem quebrar deserialização de histórico cifrado existente) e `ConservativeExamAssessor` (novo `DocumentaryReason` para extração visual sem texto digital / sem ancoragem).
- **`:inference-local`:** `SelectedInferenceConfiguration` (novo hash/tamanho/revisão, `CONTEXT_TOKENS = 4096`, `OUTPUT_TOKENS = 1536`, `VISION_BACKEND = GPU`), `FreshGemmaEngine` (suporte a `Content.ImageBytes` + `visionBackend = Backend.GPU()`, `audioBackend = null`, `maxNumImages = 1`), `InferenceProcessProtocol` / `OneShotInferenceService` / `ProcessInferenceClient` (transporte em memória de bytes da faixa PNG/JPEG com limite estrito de IPC e sem gravação em disco).
- **`:data-private-pdf` e `:app`:** `PdfPageBandSlicer` (recorte em memória de faixas horizontais sobrepostas do `Bitmap` do `PdfPageSnapshot`, detecção de faixas vazias e codificação limitada em bytes), atualização de `ExamRunner`, `ExamUiMapper` (nota documental para extração visual e aviso de conferência) e testes unitários/instrumentados (incluindo verificação offline no Galaxy S25).
- **Governança e privacidade:** Mantém 100% offline (sem `INTERNET`), sem telemetria/logs clínicos, `Engine` e processo `:inference` novos por faixa, persistência cifrada com Keystore e compatibilidade de leitura com histórico cifrado pré-existente.
