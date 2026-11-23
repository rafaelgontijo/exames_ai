# Design

## Context

Veja [proposal.md](proposal.md) para a motivação e o escopo. Hoje o repositório mantém duas variantes no módulo `:app` controladas por `-PlegacyWorkbench`:
- **Padrão (`app/src/extraction`):** tela única em `ExamScreen.kt` + `ExamViewModel.kt`, persistindo apenas um exame em `CurrentExamStore` (`:data-private-pdf`), exibindo resultados de `ExtractExam` sem classificação determinística de faixa e mostrando a página do PDF dentro de um `AlertDialog`.
- **Legada (`app/src/workbench`):** navegação multi-tela com Hilt, Room, WorkManager, Tesseract, CameraX, TTS e módulos `:feature-import`, `:feature-results`, `:feature-history`, `:data-documents`, `:data-local` e `:validation-fixtures`.

O novo design oficial está definido no projeto Stitch **`Meu Exame — MVP`** (`2627731492696541504`, Design System `assets/16423656734733007448`, [`.stitch/DESIGN.md`](../../../.stitch/DESIGN.md) e [`.stitch/metadata.json`](../../../.stitch/metadata.json)) com 6 telas mobile (`390px`), alinhadas aos tokens de `:core-ui` (`ExamesTokens`, `ExamesTypography` com Figtree local offline) e às restrições de privacidade e governança de `AGENTS.md`.

## Goals / Non-Goals

**Goals:**
- Unificar o aplicativo Android em uma única base limpa (`app/src/main`) sem `-PlegacyWorkbench`, reduzindo a árvore de módulos para `:domain`, `:core-ui`, `:data-private-pdf`, `:pdf-local`, `:inference-local`, `:app` e `:ui-checks` (mantendo `:pdf-spike` se usado como gate técnico isolado).
- Implementar fielmente as 6 telas do Stitch (`1. Início`, `2. Enviar exame`, `3. Resultado` com filtros, `4. Ajuda e revisão`, `5. Processando laudo` página a página e `6. Ver no laudo` com destaque por campo) mais a aba `Exames` (histórico completo) usando Jetpack Compose e Navigation Compose sem estado clínico em rotas ou `SavedStateHandle`.
- Introduzir histórico local cifrado de múltiplos exames em `:data-private-pdf` com migração automática e sem perdas a partir de `current-exam`.
- Avaliar cada `ExamObservation` em `:domain` de forma 100% determinística e conservadora (reaproveitando `ConservativeNumberParser` e a semântica de `DeterministicRangeEvaluator` com fallback estrito `NEEDS_REVIEW`).
- Atualizar `:ui-checks` e testes automatizados para validar acessibilidade, contraste claro/escuro, alvos ≥ 48 dp e escala de fonte de 200% nas novas telas.

**Non-Goals:**
- Alterar o protocolo de inferência LiteRT-LM / Gemma 4 E2B (`exam-extraction/4`, `exam/2`), pesos do modelo ou limites de payload em `ExtractExam` (exceto expor callback estruturado de conclusão por página para a tela de progresso).
- Reintroduzir câmera, OCR Tesseract, leitura em voz alta (TTS), compartilhamento externo, gabaritos/bancada de validação comparativa ou permissões de rede.
- Realizar normalização automática de unidades diferentes (ex.: converter `mg/dL` em `mmol/L`) ou selecionar faixas condicionais por perfil do paciente.

## Decisions

### 1. Arquitetura unificada sem Hilt/Room e com módulos enxutos
- **Decisão:** Consolidar o app em `app/src/main`, remover o flag `legacyWorkbench` de `app/build.gradle.kts` e remover do `settings.gradle.kts` e do repositório os módulos exclusivos da bancada (`:data-documents`, `:data-local`, `:feature-import`, `:feature-results`, `:feature-history`, `:validation-fixtures`) bem como suas dependências exclusivas (Hilt, Room, WorkManager, Tesseract, CameraX e repositório JitPack). Manter injeção manual simples em `MainActivity` / `ViewModelProvider.Factory` (como já faz o fluxo `extraction` atual), adicionando apenas `androidx.navigation:navigation-compose` ao `:app`.
- **Alternativas consideradas:** Manter `:feature-*` com Hilt e adaptá-los ao novo `ExtractExam`. Rejeitada porque esses módulos carregam modelos, repositórios Room, câmera, TTS, compartilhamento e bancada incompatíveis com a arquitetura enxuta de PDF+IA local e aumentam tempo de build e superfície de manutenção.

### 2. Navegação Compose com rotas opacas e zero PII
- **Decisão:** Adotar `NavHost` em `:app` com 7 destinos enxutos e zero texto clínico em argumentos:
  - Abas com barra inferior (`Início`, `Exames`, `Ajuda`): `home`, `exams`, `help`.
  - Fluxos sem barra inferior (stack dedicado): `send`, `processing/{examId}`, `results/{examId}`, `evidence/{examId}/{observationIndex}` (ou `evidence/{examId}` + seleção mantida em memória no `ExamAppViewModel` para evitar até índices estruturais na rota, passando apenas `examId` opaco).
  - **Escolha final de rota de evidência:** `evidence/{examId}` mantendo o `sourceResult.id` selecionado apenas em memória no ViewModel (nunca em `SavedStateHandle` ou `Bundle`). Se a Activity for recriada, volta de forma segura para `results/{examId}` sem persistir seleção clínica.
- **Alternativas consideradas:** Manter navegação manual por `when (state)` em um único Composable. Rejeitada porque o design agora tem barra inferior com 3 abas + telas empilhadas (`send`, `processing`, `results`, `evidence`), onde `NavHost` trata back stack, acessibilidade e restauração apenas de destino de forma previsível.

### 3. Histórico cifrado multi-exame sobre `EncryptedArtifactStore` (`:data-private-pdf`)
- **Decisão:** Criar `EncryptedExamHistoryStore` em `:data-private-pdf` reutilizando `EncryptedArtifactStore` (AES-GCM + Android Keystore) e mantendo cada exame isolado em `artifacts/<uuid>/`:
  - `original.enc`: PDF original validado.
  - `ai-extraction-v1.enc`: JSON cifrado de `ExamExtraction`.
  - Índice cifrado em `artifacts/_history_index/index-v1.enc` (escrevendo via arquivo temporário + replace atômico dentro do `Mutex` e com nova escrita segura no `EncryptedArtifactStore`, adicionando método `overwrite` atômico dedicado para o índice sem relaxar imutabilidade de artefatos de exame).
  - Cada entrada do índice (`ExamHistoryEntry`) guarda apenas metadados não identificáveis: `id` (UUID opaco), `importedAtEpochMillis`, `pageCount`, `totalCount`, `reviewCount`, `withinRangeCount`, `unclassifiableCount`, `hasPageIssues`, `interrupted: Boolean`. Nenhum nome de paciente, nome de arquivo do usuário ou valor clínico entra no índice nem em nomes de arquivo.
  - **Migração de `current-exam`:** Na inicialização, se `noBackupFilesDir/current-exam` existir, o store lê o UUID antigo, verifica se `original.enc` / `ai-extraction-v1.enc` existem, cria a entrada correspondente no índice cifrado (computando as contagens a partir da extração se presente ou marcando `interrupted = true` se ausente) e remove o ponteiro `current-exam` somente após o índice cifrado estar confirmado em disco.
  - **Arquivos legados da bancada (Room/SQLite):** Não são tocados nem apagados automaticamente na atualização.
- **Alternativas consideradas:** Usar SQLCipher ou Room para o índice. Rejeitada para evitar dependências nativas extras ou manter Room apenas para um índice pequeno de dezenas de entradas — um JSON cifrado com AES-GCM e troca atômica é mais simples, auditável e mantém a mesma raiz de confiança do Android Keystore.

### 4. Avaliador conservador em `:domain` para `ExamObservation`
- **Decisão:** Adicionar em `br.com.exames.domain.extraction` um avaliador puro `ConservativeExamAssessor` (sem Android SDK) que transforma cada `ExamObservation` em `AssessedObservation(observation, status, reason)` usando:
  - `DocumentaryStatus`: `WITHIN_RANGE` (`Na referência`), `NEEDS_REVIEW` (`Revisar`), `NOT_CLASSIFIABLE` (`Sem faixa`).
  - `DocumentaryReason`: `WITHIN_DOCUMENT_RANGE`, `ABOVE_DOCUMENT_RANGE`, `BELOW_DOCUMENT_RANGE`, `UNCERTAIN_REFERENCE_LINK`, `MULTIPLE_OR_CONDITIONAL_REFERENCE`, `UNIT_MISMATCH_OR_MISSING`, `CENSORED_OR_AMBIGUOUS_VALUE`, `INVALID_REFERENCE_SYNTAX`, `NO_REFERENCE`, `NON_NUMERIC_RESULT`.
  - **Regras estritas (nunca afrouxar `NEEDS_REVIEW`):**
    1. Se `observation.referenceNeedsReview == true` → `NEEDS_REVIEW` (`UNCERTAIN_REFERENCE_LINK`).
    2. Se `observation.reference == null` ou texto em branco → `NOT_CLASSIFIABLE` (`NO_REFERENCE`).
    3. Analisar `observation.result` com `ConservativeNumberParser`: se texto qualitativo/não numérico (sem dígitos e sem comparador), → `NOT_CLASSIFIABLE` (`NON_NUMERIC_RESULT`); se `parsed.needsReview` ou `parsed.comparator != EQ` (ex.: `< 0,5`, `> 100`) → `NEEDS_REVIEW` (`CENSORED_OR_AMBIGUOUS_VALUE`).
    4. Analisar `observation.reference.text` com parser conservador estrito de faixa única:
       - Rejeitar para `NEEDS_REVIEW` (`MULTIPLE_OR_CONDITIONAL_REFERENCE`) qualquer texto com múltiplas linhas/faixas, separadores `;`, ` ou `, rótulos condicionais (`:` com texto descritivo como sexo, idade, risco, jejum, trimestre, adulto/criança, desejável/ótimo/alto).
       - Aceitar apenas uma única faixa limpa (`A a B`, `A até B`, `A - B`, `A – B`, `< B`, `<= B`, `≤ B`, `> A`, `>= A`, `≥ A`, `abaixo de B`, `inferior a B`, `até B`, `acima de A`, `superior a A`), com unidade explícita.
       - Comparação de unidade: normalizar apenas espaços extras e micro (`µ`/`μ`) sem jamais converter escalas (`mg/dL` != `g/dL`, `%` != `/mm³`); se a unidade estiver ausente na medição ou na referência quando a outra possui unidade, ou se divergirem → `NEEDS_REVIEW` (`UNIT_MISMATCH_OR_MISSING`). Quando a referência omite unidade mas a medição veio de coluna explícita sem unidade impressa na referência, exigir compatibilidade segura ou cair em `NEEDS_REVIEW` (decisão conservadora: se unidade na referência está ausente e na medição está presente, **exigir mesma unidade explícita ou fallback `NEEDS_REVIEW`**, mantendo alinhamento estrito com `DeterministicRangeEvaluator` linha 21!).
    5. Delegar a comparação numérica de limites inclusivos/exclusivos à mesma lógica validada de `DeterministicRangeEvaluator` → `WITHIN_RANGE`, ou `NEEDS_REVIEW` (`BELOW_DOCUMENT_RANGE` / `ABOVE_DOCUMENT_RANGE`).
  - Adicionar `ExamSummaryProjection` em `:domain` para calcular contagens (`total`, `reviewCount`, `withinRangeCount`, `unclassifiableCount`), listas filtradas (`ALL`, `NEEDS_REVIEW`, `WITHIN_RANGE`, `NOT_CLASSIFIABLE`) mantendo ordem documental e rótulos seguros sem diagnóstico.
- **Alternativas consideradas:** Classificar na camada de UI ou deixar a IA propor o status (`Normal`/`Alterado`). Rejeitada frontalmente por `AGENTS.md`: classificação deve ser determinística, auditável e testada unitariamente em `:domain`, nunca delegada à IA.

### 5. Progresso página a página e visualizador dedicado `Ver no laudo`
- **Decisão:**
  - Estender o callback de progresso de `ExtractExam.extract` (com default retrocompatível) ou adicionar `onPageFinished: (ExamPageOutcome) -> Unit` para que a tela `5. Processando laudo` exiba em tempo real cada página concluída com sua contagem de itens verificados (`"Página 1 · 21 itens verificados"`), a página em andamento (`"Página 3 · Lendo agora..."`) e as páginas aguardando (`"Páginas 4 a 6 · Aguardando"`), além de percentual calculado a partir de páginas e blocos.
  - Substituir `SourceDialog` pela tela dedicada `6. Ver no laudo` (`EvidenceScreen`), reaproveitando o carregamento seguro de bitmap em memória (`PdfPreviewRenderer`) e adicionando:
    - Cartão superior com nome do exame, chip de estado, valor extraído e bloco com o texto bruto completo de `reference.text`.
    - Controle segmentado `Página inteira` / `Recorte` (padrão `Recorte` ou `Página inteira` alternável instantaneamente).
    - Chips de destaque por campo (`Resultado` = regiões de `name` + `sourceResult` + `unitCitation`; `Referência` = regiões de `reference`, desabilitado se ausente).
    - Validação de geometria no desenho (regiões dentro dos limites da página; fallback sem destaque parcial em caso de coordenadas degeneradas, mantendo a página visível e aviso claro).

### 6. Sistema visual `:core-ui` alinhado a `.stitch/DESIGN.md`
- **Decisão:** Expandir `core-ui/ExamesComponents.kt` e `ExamesTokens.kt` com componentes reutilizáveis usados pelas 6 telas (preservando contraste claro/escuro validado por `ThemeContrastTest`):
  - `ExamesHeroCard` (cartão primário `#1F5FBF` com raio `20.dp`, bloco de ícone em `#E8F1FC` e CTA invertido branco de `56.dp`).
  - `ExamesReviewBanner` (cartão de revisão `#FFF2D9` / escuro `#4A3A00` com borda sutil, ícone circular e ação `Ver no laudo`).
  - `ExamesFilterChip` (pílula selecionável com altura mínima `48.dp` e contraste ≥ 3:1 na borda).
  - `ExamesTopBar` (seta de retorno ≥ `48.dp` + título `titleMedium`/`titleLarge` + badge opcional à direita).
  - `ExamesStepCard`, `ExamesInfoBox`, `ExamesAlertBox` (aviso não diagnóstico com ícone + texto claro).
  - Atualizar `MainActivity` para aplicar `background = MaterialTheme.colorScheme.background` (`#F4F8FD` claro / `#0E1726` escuro) na raiz, corrigindo o contraste dos cartões brancos sobre o canvas azul-gelo.

## Risks / Trade-offs

- **[Remoção de módulos legados pode quebrar testes ou scripts antigos]** → Auditar referências a `legacyWorkbench`, `:feature-*`, `:data-local`, `:data-documents`, `:validation-fixtures` em `settings.gradle.kts`, `ui-checks`, `docs/` e scripts em `tools/` antes da remoção; manter `tools/workbench/run_authorized_example.py` funcional para o fluxo padrão (`--protocol exam --via-saf`) e atualizar `app/src/androidTest` (migrando testes relevantes de `src/extractionTest` para `src/androidTest`).
- **[Testes de aceite e UI existentes em `app/src/extractionTest` esperam rótulos da tela antiga]** → Atualizar `ExamScreenTest`, `PickerSelectionTest`, `PickerCancellationTest`, `CurrentExamStoreTest` e `PrivateExamAcceptanceTest` / `ExamPickerAutomation` para os novos rótulos e fluxo de navegação (`Selecionar PDF`, `Cancelar leitura`, `Ver no laudo`, exclusão individual), mantendo intacto o protocolo de prova privada no S25.
- **[Conservadorismo na comparação de unidades pode marcar mais itens como `Revisar` quando o laudo omite a unidade na coluna de referência]** → Trade-off intencional e desejável conforme `AGENTS.md` ("Nunca afrouxar o fallback NEEDS_REVIEW"): transparência documental e conferência no laudo têm prioridade absoluta sobre classificar automaticamente como "Na referência" sem certeza explícita.
- **[Escrita do índice cifrado em concorrência ou falha de energia]** → Toda mutação de `EncryptedExamHistoryStore` executa sob `Mutex` em `Dispatchers.IO`, grava em arquivo temporário autenticado antes do `renameTo` atômico e faz fail-closed se o ponteiro/índice falhar.

## Migration Plan

1. **Preparar `:domain` primeiro:** implementar `ConservativeExamAssessor`, `ExamSummaryProjection` e callback `onPageFinished` em `ExtractExam` com suíte completa de testes unitários JVM (`./gradlew :domain:test`).
2. **Evoluir `:data-private-pdf`:** implementar `EncryptedExamHistoryStore` com migração automática de `current-exam` e testes unitários/instrumentados cobrindo múltiplos exames, cancelamento e migração.
3. **Expandir `:core-ui`:** adicionar os novos componentes do Design System Stitch mantendo `ThemeContrastTest` e suporte a tema escuro/fonte 200%.
4. **Refatorar `:app` e unificar a build:** mover o código padrão de `app/src/extraction` para `app/src/main` (e `app/src/extractionTest` para `app/src/androidTest`), implementar o grafo Navigation Compose com todas as telas do Stitch, remover `-PlegacyWorkbench` e os módulos legados de `settings.gradle.kts` / repositório.
5. **Atualizar `:ui-checks`, testes e documentação:** portar `:ui-checks` para validar todas as 6 novas telas em claro/escuro e 100%/200%, executar `./gradlew assembleDebug test` e validação instrumentada em dispositivo/emulador disponível, e atualizar `README.md` e documentação afetada.
