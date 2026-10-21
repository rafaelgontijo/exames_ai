# Exames Offline — scaffolding Android nativo

Base modular Kotlin + Jetpack Compose + Material 3 para leitura e sinalização referencial determinística. Não fornece diagnósticos, recomendações clínicas nem urgência. O DOCX fornecido foi usado como referência de arquitetura; o escopo desta entrega é o scaffolding solicitado no texto colado.

## Estrutura e dependências

```text
exames_ai/
├── AGENTS.md
├── gradle/libs.versions.toml
├── settings.gradle.kts
├── build.gradle.kts
├── domain/                  # JVM pura: modelos, contratos, casos de uso, avaliador e testes
├── data-documents/          # SAF, PdfRenderer e ML Kit com modelo Latin incluído
├── data-local/              # Room, Keystore/AES-GCM, DataStore e worker de limpeza
├── feature-import/          # Compose: seletor SAF e inspeção inicial de formato/tamanho
├── feature-results/         # Compose: estado inicial de revisão
├── feature-history/         # Compose: estado inicial do histórico
├── inference-experimental/  # Stub isolado, não incluído no app
└── app/                     # Hilt, tema Material 3 e Navigation Compose
```

`domain` não depende de Android. `data-documents` e `data-local` dependem de `domain`; `feature-import` depende de `domain` e `data-documents`; resultados e histórico dependem apenas de `domain`. O app conecta features e módulos de dados. Não há SDK de rede, telemetria, conta, backend ou permissão INTERNET. Downloads de dependências durante o build não fazem parte do funcionamento do app.

## Build

Requer JDK 17 ou 21 e Android SDK Platform 36. Configure `ANDROID_HOME` ou `sdk.dir` em `local.properties` (não versionado). minSdk 23 é provisório e deve ser validado em aparelhos de entrada.

```bash
./gradlew assembleDebug
./gradlew test
./gradlew :domain:test
./gradlew :app:connectedDebugAndroidTest
```

O último comando requer aparelho/emulador. APK: `app/build/outputs/apk/debug/app-debug.apk`. Relatórios JVM: `domain/build/reports/tests/test/index.html`. No ambiente cloud preparado, execute `source /workspace/.android-dev/env.sh` antes dos comandos. O Wrapper verifica o SHA-256 da distribuição.

## O que já existe

- Oito módulos com Gradle Kotlin DSL, catálogo de versões e manifests Android.
- Navegação importação → resultados/histórico, ViewModel + StateFlow e seleção de PDF/JPEG/PNG via SAF. A tela apenas inspeciona a seleção: não salva nem processa laudos ainda.
- Adaptadores para renderização de páginas e OCR on-device com página, texto bruto e bounding box. Coordenadas em pixels da imagem renderizada; confiança desconhecida permanece nula.
- Modelos com textos brutos e proveniência; contratos de repositórios/parser; caso de uso e avaliação determinística testável com relógio injetável.
- Parser numérico conservador com vírgula/ponto decimal, agrupamentos explícitos e preservação dos comparadores. `1,234` e `1.234` exigem revisão; resultados censurados não são classificados automaticamente.
- Room com seis tabelas, chaves estrangeiras, DAOs, conversores e schema exportado. Sem migração destrutiva.
- Store de artefatos privado com AES-GCM, chave não exportável do Keystore, escrita em arquivo temporário criptografado e rename final. Limite provisório de 20 MB.
- DataStore apenas para preferências não sensíveis. Backups e transferência padrão excluídos; sem logs de conteúdo clínico.
- Intenção de exclusão transacional: apagar registros em cascata e gravar outbox na mesma transação; WorkManager limpa diretórios e só então confirma a remoção. DB e filesystem não possuem transação única; a outbox permite retentativa após falhas.

## Regras de avaliação

Regra `REF_NUMERIC/1.0.0`: comparação decimal exata, limites inclusivos/exclusivos e referências `<`, `<=`, `>`, `>=`. Exige confirmação, unidade explícita idêntica e referência declarada aplicável. Ambiguidade, associação incorreta, contexto desconhecido e limites inválidos resultam em `NEEDS_REVIEW`. Referência ausente e resultados qualitativos produzem `NOT_CLASSIFIABLE` quando as demais pré-condições são conhecidas. Não converte unidades nem inventa intervalos. Os textos brutos nunca são modificados pelo avaliador.

## Próximas implementações e limites

Este projeto não é o MVP completo nem está validado para uso clínico. Repositórios de domínio ainda são contratos; não há integração de persistência de laudos nas telas, revisão/edição manual, busca, reabertura ou exclusão acionada pela UI. Integrar essas entregas antes de apresentar resultados reais.

- Conectar importação à cópia criptografada, hash, preview multipágina e parser de layouts-alvo. O seletor não mantém grants persistentes nem copia o conteúdo; não agenda processamento a partir da URI atual.
- Integrar WorkManager ao pipeline de importação/OCR com checkpoint por página e cancelamento. O worker existente cuida somente da limpeza.
- `PdfRenderer` não extrai texto digital; avaliar biblioteca/licença para a camada textual. OCR Latin é um adaptador inicial, não um parser clínico.
- Adaptar leitura de PDF criptografado à prévia sem deixar temporários persistentes em claro.
- Implementar confirmação e correções append-only sem sobrescrever os textos capturados; validar aplicabilidade clínica separadamente.
- Ligar a exclusão a `DocumentDao.enqueueCompleteDeletion` e garantir que todo artefato pertença ao diretório opaco do documento. A limpeza periódica está registrada, mas o fluxo UI/repositório ainda não está conectado. Exclusão física segura de blocos flash não é garantida por delete de arquivos.
- Room usa SQLite privado sem criptografia adicional do banco nesta base. Antes de persistir dados clínicos reais, decidir e implementar a proteção específica do banco; AES-GCM atual protege somente arquivos de artefatos. Backup desativado não substitui criptografia.
- Validar documentos corrompidos, tamanho real de streams, orientação de imagem, PDFs não seekable, memória, acessibilidade, operação offline e OCR em dispositivo. Testes JVM não validam essas propriedades.
- Realizar revisão clínica, jurídica/LGPD e regulatória antes de distribuição pública. Inferência permanece opcional e isolada.

Somente fixtures sintéticas nos testes. Regras para futuras alterações estão em `AGENTS.md`.
