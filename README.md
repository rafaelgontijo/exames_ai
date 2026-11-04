# Exames Offline — bancada Android de validação interna

App Kotlin/Compose offline para validação interna da extração de bioquímica básica e avaliação referencial determinística. Não fornece diagnósticos, recomendações clínicas nem urgência. Importação privada, pipeline recuperável, OCR português Tesseract, parser, revisão e relatórios estão conectados à UI. A inferência LiteRT-LM/Gemma 4 E2B passou nos controles nativos do S25; o fluxo completo passou em instalação limpa offline. Resultados e limites em [S25_FINAL_VALIDATION.md](docs/S25_FINAL_VALIDATION.md).

## Estrutura e dependências

```text
exames_ai/
├── AGENTS.md
├── gradle/libs.versions.toml
├── settings.gradle.kts
├── build.gradle.kts
├── domain/                  # JVM pura: modelos, contratos, casos de uso, avaliador e testes
├── data-documents/          # SAF, prévia privada, orientação e Tesseract português empacotado
├── data-local/              # Room, Keystore/AES-GCM, DataStore e worker de limpeza
├── feature-import/          # Compose: SAF, pacotes de corpus, modos e preparação de modelo
├── feature-results/         # Compose: evidência, revisão, gabaritos e relatórios
├── feature-history/         # Compose: histórico, reabertura e exclusão
├── pdf-local/               # Adaptador Java do PDFium/JNI corrigido
├── pdf-spike/               # Ensaio técnico isolado
├── validation-fixtures/     # Geradores sintéticos somente para testes
├── inference-local/         # Adaptador LiteRT-LM; integração da bancada em andamento
└── app/                     # Hilt, tema Material 3 e Navigation Compose
```

`domain` não depende de Android. `data-documents` e `data-local` dependem de `domain`; `feature-import` depende de `domain` e `data-documents`; resultados e histórico dependem apenas de `domain`. O app conecta features e módulos de dados. Não há SDK de rede, telemetria, conta, backend ou permissão INTERNET. Downloads de dependências durante o build não fazem parte do funcionamento do app.

## Build

O início da bancada de validação inclui contratos JVM e ferramentas de corpus sintético. Veja [docs/CORPUS.md](docs/CORPUS.md) para geração, formato, revisão e limites de evidência. A importação protegida de pacotes conecta o corpus ao processamento Android; veja [CORPUS_IMPORT.md](docs/CORPUS_IMPORT.md).

Requer JDK 21 para o adaptador PDF e Android SDK Platform 36. Antes do primeiro build, preparar e fazer staging do AAR nativo corrigido conforme [DOCUMENT_EXTRACTION.md](docs/DOCUMENT_EXTRACTION.md). O build também exige o modelo selecionado no cache externo ou `-PexamesModelPath=/caminho/modelo.litertlm`, conforme [BUNDLED_INFERENCE.md](docs/BUNDLED_INFERENCE.md). O OCR também requer o peso português fixado em [OCR_COMPATIBILITY.md](docs/OCR_COMPATIBILITY.md). Configure `ANDROID_HOME` ou `sdk.dir` em `local.properties` (não versionado). minSdk 24 (Android 7.0), aprovado para compatibilidade com LiteRT-LM. O desempenho ainda precisa ser validado nos aparelhos-alvo.

```bash
./gradlew assembleDebug
./gradlew test
./gradlew :domain:test
./gradlew :app:connectedDebugAndroidTest
```

O último comando requer aparelho/emulador. APK: `app/build/outputs/apk/debug/app-debug.apk`. Relatórios JVM: `domain/build/reports/tests/test/index.html`. No ambiente cloud preparado, execute `source /workspace/.android-dev/env.sh` antes dos comandos. O Wrapper verifica o SHA-256 da distribuição.

## O que já existe

- Módulos com Gradle Kotlin DSL, catálogo de versões e manifests Android.
- Importação individual de PDF/JPEG/PNG via SAF para cópia privada, criação de execução e processamento sem IA com Hilt/StateFlow/WorkManager. A UI mostra progresso por página, cancelamento e reprocessamento em nova execução.
- Histórico conectado, reabertura de campos/avaliação/correções/gabaritos/comparações e prévia autenticada da fonte privada, com [destaque por campo/página](docs/EVIDENCE_REVIEW.md); exclusão com confirmação e limpeza recuperável. Fluxo e limites em [WORKBENCH_FLOW.md](docs/WORKBENCH_FLOW.md).
- [Correção de extração, revisão independente de gabarito e reavaliação/reextração](docs/REVIEW_WORKFLOW.md) em ações distintas. Saídas vinculadas e comparações por versão preservam resultados e métricas originais; rascunhos ficam somente em memória.
- Adaptadores para renderização de páginas e OCR on-device com página, texto bruto e bounding box. Coordenadas em pixels da imagem renderizada; confiança desconhecida permanece nula.
- Modelos com textos brutos e proveniência; contratos de repositórios/parser; caso de uso e avaliação determinística testável com relógio injetável.
- Parser numérico conservador com vírgula/ponto decimal, agrupamentos explícitos e preservação dos comparadores. `1,234` e `1.234` exigem revisão; resultados censurados não são classificados automaticamente.
- Schema legado v1 preservado e schema protegido v3 com payloads autenticados, relações entre documento/execução, auditoria append-only e tombstones permanentes. Migração por cópia/troca recuperável e atualização explícita 2→3, sem fallback destrutivo.
- Store de artefatos privado com AES-GCM, chave não exportável do Keystore, escrita em arquivo temporário criptografado e rename final. Limite provisório de 20 MiB por documento.
- DataStore apenas para preferências não sensíveis. Backups e transferência padrão excluídos; sem logs de conteúdo clínico.
- Intenção de exclusão transacional: apagar registros em cascata e gravar outbox na mesma transação; WorkManager limpa diretórios e só então confirma a remoção. DB e filesystem não possuem transação única; a outbox permite retentativa após falhas.

## Regras de avaliação

Regra `REF_NUMERIC/2.0.0`: comparação automática decimal exata, limites inclusivos/exclusivos e referências `<`, `<=`, `>`, `>=`. A confirmação humana é metadado separado; exige unidade explícita idêntica e referência declarada aplicável. Ambiguidade, associação incorreta, contexto desconhecido e limites inválidos resultam em `NEEDS_REVIEW`. Referência ausente e resultados qualitativos produzem `NOT_CLASSIFIABLE` quando as demais pré-condições são conhecidas. Não converte unidades nem inventa intervalos. Os textos brutos nunca são modificados pelo avaliador.

## Estado de validação e limites

OpenSpec `add-android-biochemistry-validation`: **51/51 tarefas de implementação concluídas**. Build padrão, 269 testes JVM e aceite técnico do fluxo no S25 aprovados; evidências em [VALIDACAO.md](docs/VALIDACAO.md).

Esta é uma bancada interna sem validação clínica. Importação individual e de pacotes, histórico, revisão e relatórios estão conectados. Acessibilidade passou nos controles em emulador; o protocolo assistido v5 e o aceite A/B completo passaram no S25 como verificação técnica. Na amostra de oito casos DEVELOPMENT, uma página foi aceita pelo modelo e sete tiveram fallback; isso não aprova a qualidade geral da extração.

- A importação de pacotes está conectada conforme [CORPUS_IMPORT.md](docs/CORPUS_IMPORT.md). O fluxo individual já usa o [importador protegido](docs/PROTECTED_IMPORT.md), o [pipeline recuperável](docs/PROCESSING_PIPELINE.md), os [adaptadores privados](docs/DOCUMENT_EXTRACTION.md) e o [parser dos cinco analitos/dois layouts](docs/BIOCHEMISTRY_PARSER.md).
- Relatórios na UI, TalkBack, fonte 200% e contraste foram verificados em emulador; Edição, revisão de gabarito, comparação por versão e reavaliação/reextração já estão conectadas; não modificam métricas originais ou ambiguidades silenciosamente.
- Histórico, reabertura e exclusão estão conectados a `ValidationRepository`; cada artefato pertence ao diretório opaco do documento. Exclusão física segura de blocos flash não é garantida por delete de arquivos.
- A abertura do banco passa por ProtectedDatabaseFactory, com migração do schema v1 para payloads AES-GCM/Keystore e schema atual v3. Metadados operacionais permanecem em claro; não se trata de criptografia integral do contêiner SQLite. Veja [proteção do banco](docs/PROTECTED_DATABASE.md) e [exclusão coordenada](docs/DELETION_COORDINATION.md).
- O aceite final no S25 passou com quatro formatos, dois modos, revisão, reinício e exclusão; veja [S25_FINAL_VALIDATION.md](docs/S25_FINAL_VALIDATION.md), incluindo erros, denominadores e limites. Evidências técnicas e limites dos controles já executados estão em [VALIDACAO.md](docs/VALIDACAO.md).
- Inferência textual LiteRT-LM está integrada como P0, ativa somente no S25 de referência verificado, com modo sem IA para comparação/fallback. O spike selecionou Gemma 4 E2B GPU; configuração fixada em [selection.json](validation/models/selection.json), evidência e limitações em [MODEL_COMPARISON_S25.md](docs/MODEL_COMPARISON_S25.md). O adaptador anterior, DI e instalação limpa com modelo empacotado passaram no S25 em modo avião; [procedimento e limites](docs/BUNDLED_INFERENCE.md). Validação de evidência e [diário protegido de tentativas](docs/INFERENCE_TRACE.md) estão implementados. Processo dedicado, canal final, prefixos isolados, limites e cancelamento passaram também com o modelo real no S25. O prompt v5 usa [apresentação literal da fonte](docs/SOURCE_PRESENTATION.md); o PDF digital simple-v1 de desenvolvimento passou com cinco observações, enquanto o layout com notas permanece em fallback.
- O app aceita somente campos com schema e evidência validados, preservando divergências e NEEDS_REVIEW; a classificação permanece no motor determinístico. Usar Engine nova por solicitação e não persistir/exibir raciocínio interno. Pesos ficam fora do Git e a distribuição interna deve permitir a primeira execução offline.
- Revisão clínica, jurídica/LGPD e regulatória continuam necessárias antes de distribuição pública. Famílias HELD_OUT expostas durante o diagnóstico inicial estão registradas em [CORPUS.md](docs/CORPUS.md); os controles atuais usam somente DEVELOPMENT e não constituem teste independente de generalização.

O procedimento físico restante está em [FINAL_ACCEPTANCE.md](docs/FINAL_ACCEPTANCE.md); o último build/auditoria local está em [VALIDACAO.md](docs/VALIDACAO.md).

Somente fixtures sintéticas nos testes. Regras para futuras alterações estão em `AGENTS.md`.
