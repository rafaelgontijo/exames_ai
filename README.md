# Meu Exame

App Android nativo em Kotlin e Jetpack Compose para extrair resultados de exames em PDF digital com IA local no aparelho e conferir cada valor diretamente no laudo original.

## Experiência e design

A interface segue o projeto Stitch **`Meu Exame — MVP`** (`2627731492696541504`, Design System `assets/16423656734733007448`, [`.stitch/DESIGN.md`](.stitch/DESIGN.md)) com seis telas principais e barra inferior (`Início`, `Exames`, `Ajuda`):

1. **Início:** cabeçalho da marca, atalho para ajuda, cartão de importação offline, passo a passo em 3 etapas e lista recente **Meus exames**.
2. **Enviar exame:** seleção de PDF digital, acesso ao histórico local, dicas para leitura fiel e aviso de privacidade offline cifrada.
3. **Processando laudo:** acompanhamento página a página e por bloco da IA local, com cancelamento seguro que descarta apenas a leitura interrompida.
4. **Resultado:** banner de conferência, filtros por estado (**Todos**, **Revisar**, **Na referência** e **Sem faixa** quando presente), cartões em ordem documental com classificação determinística conservadora e atalho **Ver no laudo**.
5. **Ver no laudo:** visualização dedicada da página original do PDF com alternância **Recorte / Página inteira**, filtros de destaque por campo (**Resultado / Referência**) e texto bruto preservado da referência.
6. **Ajuda e revisão:** guia de conferência documental, legenda das marcações, privacidade offline e aviso explícito de limite não diagnóstico.

Todos os PDFs importados e extrações ficam em armazenamento privado criptografado com AES-GCM e chaves do Android Keystore, suportando histórico de múltiplos exames com exclusão individual ou total.

## Build e testes

Use JDK 21 e Android SDK Platform 36. O app instala a partir do Android 7.0 (API 24). O S25 é o aparelho de referência para a inferência GPU. Prepare o AAR fixado do PDFium e os pesos Gemma 4 E2B antes do build. [Configuração do PDFium](docs/PDF_COMPATIBILITY.md) · [Modelo e distribuição](docs/BUNDLED_INFERENCE.md)

```sh
./gradlew assembleDebug test
./gradlew :ui-checks:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

O APK padrão fica em `app/build/outputs/apk/debug/app-debug.apk`. A arquitetura unificada (sem a antiga variante `legacyWorkbench`) é composta por:
- `:domain`: contratos de extração, validação de evidência e avaliação determinística conservadora (`ConservativeExamAssessor`, `ExamSummaryProjection`).
- `:data-private-pdf`: importação SAF validada, prévia PDFium em memória e histórico cifrado multi-exame (`EncryptedExamHistoryStore`, com migração automática do ponteiro `current-exam`).
- `:core-ui`: tokens claro/escuro com Figtree offline e telas Compose do sistema visual.
- `:pdf-local` e `:inference-local`: PDFium e LiteRT-LM com pesos fixados do Gemma 4 E2B para primeira execução offline. [Seleção do modelo](validation/models/selection.json)
- `:app`: aplicação unificada com Navigation Compose de rotas estritamente opacas.
- `:ui-checks`: verificação automatizada de acessibilidade, alvos ≥ 48 dp, temas claro/escuro e escala de fonte de 200%.

## Escopo e limites

O aplicativo funciona 100% offline, sem permissão `INTERNET`, sem bibliotecas de rede e sem backups automáticos. Ele não fornece diagnósticos, recomendações clínicas ou classificação de urgência; ambiguidades de vínculo, múltiplos critérios ou unidades incompatíveis permanecem obrigatoriamente marcadas como **Revisar** (`NEEDS_REVIEW`). [Fluxo de extração e limites](docs/AI_EXAM_EXTRACTION.md) · [Experiência e acessibilidade](docs/PROTOTYPE_EXPERIENCE.md)
