# Validação técnica — 21/10/2026

Executado no ambiente cloud com JDK 21, Gradle Wrapper 8.13 e SDK Android 36:

```bash
./gradlew assembleDebug test :app:assembleDebugAndroidTest --no-daemon --console=plain
```

Resultado: **BUILD SUCCESSFUL**, 422 tarefas (132 executadas e 290 atualizadas).

- Todos os oito módulos compilados, incluindo o experimental isolado.
- 64 testes JVM: 23 de parser numérico e 41 de avaliação determinística; zero falhas, erros ou testes ignorados.
- APK de debug gerado: `app/build/outputs/apk/debug/app-debug.apk`.
- APK instrumentado de navegação Compose compilado, mas não executado. Requer aparelho/emulador.
- Manifest mesclado verificado: sem INTERNET e `allowBackup=false`.
- Estrutura de módulos, manifests XML e ausência de imports Android em `:domain` verificados.
- Wrapper executado com sucesso; distribuição fixada e verificada por SHA-256.

Avisos não bloqueantes: bibliotecas nativas empacotadas sem strip de símbolos e opções Hilt/Kapt não consumidas no source set de testes instrumentados.

Esta validação é de scaffolding e regras numéricas com fixtures sintéticas. OCR, operação offline em aparelho, acessibilidade, memória, segurança do armazenamento, exclusão após falhas e validação clínica ainda exigem testes próprios. A CI foi configurada, mas não foi executada no GitHub nesta sessão. Ver pendências no README.


## Bancada de validação — persistência v2

Execução em 21/10/2026, JDK 21, SDK 36, minSdk 24 aprovado pelo usuário:

```bash
source /workspace/.android-dev/env.sh
./gradlew assembleDebug test :data-local:connectedDebugAndroidTest --no-daemon --console=plain
```

Resultado: **BUILD SUCCESSFUL**, 438 tarefas Gradle (266 executadas, 172 atualizadas).

- 72 testes de domínio JVM aprovados.
- 5 testes do codec AES-GCM aprovados em cada variante JVM debug/release.
- 12 testes instrumentados aprovados no emulador Android 7.0/API 24 x86_64: Keystore, schema, migração/recuperação, reabertura, versões/correções, commit atômico, privacidade e limites de associação.
- A inspeção ampliada dos diretórios privados de cache/files/noBackup/databases foi verificada em execução instrumentada adicional direcionada, também aprovada.
- APK debug gerado; aapt confirmou minSdk 24 e targetSdk 36. Todos os sete módulos Android declaram minSdk 24.
- Hilt e worker de limpeza usam ProtectedDatabaseFactory; não há outra abertura Room nem fallback destrutivo no código de produção.
- Os marcadores sensíveis sintéticos não apareceram em claro no banco, journals ou diretórios privados examinados.
- OpenSpec passou na validação estrita; 14/51 tarefas estão concluídas. As tarefas restantes não foram marcadas como completas por esses checks.

A execução adicional de privacidade usou:

```bash
./gradlew :data-local:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=br.com.exames.datalocal.ProtectedRepositoryTest#persistedFilesAndJournalsDoNotContainSensitiveMarkers' --no-daemon --console=plain
```

O corpus contém 216 variantes sintéticas/1080 observações, com validação de pacote aprovada. Foram aprovados os 15 testes iniciais da suíte Python e o teste adicional direcionado de compressão excessiva, totalizando 16 casos de teste distintos. A geração em duplicata produziu o mesmo manifesto e ZIP. Ver docs/CORPUS.md.

**Limites dessa execução:** não houve execução física no S25, extração de laudos reais, OCR ponta a ponta, validação clínica ou inferência do modelo. A UI e o pipeline continuam pendentes. A obtenção do peso LFM estava bloqueada nessa etapa; o bloqueio foi superado na retomada abaixo. O runtime/modelo ainda não está incluído no APK. Não declarar o MVP completo.

## Retomada — verificação do peso LFM

Em 21/10/2026, a branch foi recuperada no commit remoto
`6a8f0528a8c6da2f77bec716a4cc8959c1794164`. Git e Hugging Face funcionaram
com acesso à rede autorizado no executor, preservando o proxy e TLS.

- Download completo: 736015744 bytes, HTTP 200.
- SHA-256 calculado no binário: `da3c0ad154b78f33537b117fbe747cc1d4fd3507479002b159998f4023f32a84`.
- Tamanho/hash coincidem com o pin, metadados LFS e manifesto de origem na revisão `f8b5b3a3eea2e3859e821ad9d9222c64d649eff7`.
- Peso mantido em `/workspace/.model-cache/`, fora do Git. Manifesto fixado e licença preservados em `validation/models/lfm2.5-1.2b-thinking/`.
- Tarefa 4.6 concluída: 15/51 tarefas. Os resultados anteriores de build/testes não foram reexecutados nesta alteração documental.

Ver comandos, termos de redistribuição e alternativa Qwen em `MODEL_DOWNLOAD.md`.
O ADB não lista dispositivos conectados nesta retomada. A tarefa 4.7 continua
pendente do spike real no S25; download e integridade não comprovam inferência,
JSON final, memória, latência, reset ou cancelamento. Nenhum benchmark foi executado.

## Retomada local — 26/10/2026

Branch remota recuperada em `1d4f301ae704de2ed4695ce230fe5c124e43ed6b`.
Novo download completo no macOS: HTTP 200, 736015744 bytes e SHA-256 real
`da3c0ad154b78f33537b117fbe747cc1d4fd3507479002b159998f4023f32a84`.
Cache externo ao Git documentado em `MODEL_DOWNLOAD.md`.

Com JDK 21.0.13 e SDK 36 locais, executado:

```bash
./gradlew assembleDebug test :app:assembleDebugAndroidTest --no-daemon --console=plain
./gradlew :inference-experimental:assembleDebugAndroidTest --no-daemon --console=plain
```

Build geral: **BUILD SUCCESSFUL**, 434 tarefas (416 executadas, 18 atualizadas).
77 testes JVM distintos aprovados, zero falhas/erros/ignorados. Testes de
persistência instrumentados anteriores não foram reexecutados nesta sessão.
O APK isolado do spike também compilou após a adição do diagnóstico sanitizado;
passou no alinhamento ZIP de 16 KiB e não declara INTERNET.

Ensaio real do LFM CPU no S25 `SM-S931B`, Android 17/API 37: a carga retornou e
a geração começou, mas o teste reprovou sem evento de resultado. Sem timeout
do controlador e sem causa identificada no primeiro relatório. O aparelho
desconectou antes da repetição diagnóstica. Não há sucesso de JSON, métricas
completas, isolamento ou benchmark A/B comprovados. Relatório, limites e
reprodução em [LFM_S25_SPIKE.md](LFM_S25_SPIKE.md).

O runtime continua restrito ao APK de teste; não foi integrado ao app. Há
incompatibilidade entre metadados Kotlin 2.4 do AAR e compilador 2.1.20 do projeto,
além de avisos D8, detalhados em `LITERT_COMPATIBILITY.md`. Nenhuma tarefa nova
foi marcada: **15/51**, com 4.7 aberta. Nenhuma validação clínica é declarada.

Após o usuário reconectar o S25, foi executada a repetição diagnóstica do mesmo
spike. `Engine.initialize()` concluiu em 15458 ms; a geração atingiu o limite de
120 s sem resultado e o controlador encerrou somente o pacote de teste. ADB
continuou listando o S25 conectado após o encerramento. O relatório preservado
em `validation/results/lfm-s25-spike-2026-10-26-diagnostic.json` registra timeout,
sem JSON, memória ou tokens disponíveis e sem causa nativa identificada. O
segundo caso planejado não executou. O progresso permanece **15/51**. Nesta
repetição não houve alterações de código nem nova execução da suíte JVM.

## Correção do spike — saída estruturada

O diagnóstico foi ampliado para separar carga/conversa/geração e registrar somente
contadores, tempos, PSS amostrada e comparações booleanas por campo, sem pensamento
ou resposta. A configuração CPU com JSON Schema, instruções explícitas, campos
obrigatórios não vazios e contexto/saída 2048/1024 passou nos dois casos sintéticos.
Cada caso criou uma Engine nova; os valores eram diferentes. Não foi fornecido
gabarito ao modelo ou ao schema. A conferência literal de todos os campos permaneceu
obrigatória. Geração: 33,279 s e 19,660 s; PSS amostrada máxima: 7,08 GiB.

A variante GPU da mesma revisão foi baixada e verificada: 736220768 bytes,
SHA-256 `51130e6e57a940bbfbb66f67698c47fd10c7051a70e7247fc8b084ccce4d54ae`.
Gerou em 1,954 s e 2,396 s, PSS amostrada de 2,06 GiB, mas só acertou o primeiro
caso. O relatório GPU permanece reprovado. Uma tentativa inicial do controle
float32 não chegou a executar por desconexão. Não inferir validade de extração
apenas de JSON bem formado.

O APK instrumentado do spike foi recompilado com sucesso a cada alteração executada.
Três testes Python do controlador passaram, incluindo heartbeat sem prorrogação
do timeout. A suíte JVM de produção não foi repetida nesta investigação de testes.
Os 77 testes JVM aprovados anteriormente não são apresentados como nova execução.
Detalhes, limitações, relatórios e reprodução em `LFM_S25_SPIKE.md`. O progresso
permanece **15/51**: escolha de backend/runtime e integração ainda não concluídas.

## Repetição GPU float32 solicitada

Executado no S25 reconectado, usando o mesmo APK já compilado, variante GPU
verificada, prompt/schema e contexto/saída 2048/1024, agora com
`ActivationDataType.FLOAT32` solicitado. A carga levou 16644 ms e a geração
2176 ms, com PSS máxima amostrada de 2763877 KiB (2,64 GiB). A resposta reprovou
nos cinco campos da primeira entrada; a segunda não foi executada. O controlador
registrou `passed=false`, sem timeout ou indicação de crash, e o S25 permaneceu
conectado após o teste. Relatório separado:
`validation/results/lfm-s25-spike-2026-10-26-gpu-float32.json`.

Não houve alterações de código nem nova suíte JVM nessa repetição. Os relatórios
anteriores foram preservados. CPU continua com evidência positiva limitada a dois
exemplos; GPU permanece reprovada e a tarefa 4.7 continua aberta, em **15/51**.

## Comparação Qwen/Gemma e conclusão do spike de modelo

Foram baixados e verificados por tamanho/SHA-256 os candidatos Qwen3 0.6B e,
por solicitação do usuário após reprovação do Qwen, Gemma 4 E2B GPU. Pins, cards,
licenças e relatórios estão versionados; pesos permanecem fora do Git.

Qwen reprovou os controles executados. Gemma também reprovou com ResponseFormat
nativo, mas passou sem essa gramática, conservando JSON completo e evidência
literal obrigatórios. A confirmação com leitor estrito passou nos dois casos
pela API síncrona (1205/1061 ms de geração) e na API assíncrona (1160/1132 ms).
São duas linhas distintas repetidas em dois modos. A maior PSS amostrada no modo
assíncrono foi 3014967 KiB (2,88 GiB). Não converter isso em acurácia do corpus.

O novo leitor rejeita duplicatas, tipos não textuais, sintaxe leniente, wrappers,
truncamento, conteúdo extra e múltiplos objetos; diagnóstico de trim/substring
nunca autoriza aprovação. Quatro testes instrumentados desse protocolo passaram
no S25. Dez testes Python do controlador passaram. O APK do spike compilou com
sucesso e passou no alinhamento ZIP de 16 KiB, sem INTERNET e com backup desabilitado.
Não houve nova execução dos 77 testes JVM ou dos instrumentados de persistência
nesta investigação; seus resultados anteriores continuam identificados separadamente.

Decisão: Gemma 4 E2B GPU para integração, sem ResponseFormat nativo neste runtime,
mantendo validação externa obrigatória, conforme `validation/models/selection.json`.
A tarefa 4.7 fica concluída pelo spike técnico e escolha documentada: **16/51**.
Integração, distribuição offline no APK, corpus amplo, quatro GB, kernel 16 KiB,
cancelamento robusto, parser e UI continuam pendentes. Detalhes e comandos em
[MODEL_COMPARISON_S25.md](MODEL_COMPARISON_S25.md). Nenhuma validação clínica é declarada.

## Continuação em 27/10/2026: importação protegida e governança

Concluídas tarefas 3.2 e 4.14; progresso confirmado pelo OpenSpec: **18/51**.
`AGENTS.md` e README agora refletem inferência P0 autorizada, com os mesmos
controles de domínio, evidência, privacidade e avaliação determinística.

O importador fornecido por Hilt lê SAF com limite real de 20 MiB, assinatura e
hash, escreve a fonte no store AES-GCM e só depois publica metadados protegidos.
Journal de IDs opacos permite recuperar importações interrompidas, preservando
fontes já registradas e removendo órfãs. A limpeza é acionada ao abrir o app,
periodicamente e antes da próxima importação. Detalhes/limites em
[PROTECTED_IMPORT.md](PROTECTED_IMPORT.md). A conexão com a tela segue pendente.

Com JDK 21 / SDK 36, foram executados:

```bash
./gradlew :data-documents:testDebugUnitTest :data-local:connectedDebugAndroidTest :app:assembleDebug
```

Resultado final: BUILD SUCCESSFUL; seis novos testes JVM passaram; **19 testes
instrumentados, zero falhas/erros/ignorados**, no Samsung SM-S931B, Android 17.
São sete novos testes de importação mais os 12 existentes de persistência,
agora também reexecutados no aparelho físico. A tarefa `:domain:test` foi
consultada em execução anterior desta etapa e ficou UP-TO-DATE; não contar isso
como nova execução dos testes de domínio. Interrupção de importação é simulada
com journal/arquivos; não é queda de energia real. Nenhuma validação clínica.

A investigação da tarefa PDF 3.1 identificou logs de tokens do documento no
PDFBox-Android 2.0.27.0 publicado. A biblioteca não foi adicionada ao APK; o spike
PDF continua pendente de decisão sobre variante auditada ou outro extrator.
Fonte/hash e pontos de logging em [PDF_COMPATIBILITY.md](PDF_COMPATIBILITY.md).

## Spike PDFium em 27/10/2026

Candidato `io.legere:pdfiumandroid:2.0.1` ensaiado em módulo Java separado
`:pdf-spike`, sem dependência do aplicativo. Build do APK instrumentado aprovado
com JDK 21/SDK 36/minSdk 24. O APK final não tem INTERNET, mantém backup desligado
e passou no alinhamento ZIP de 16 KiB. ELF arm64/x86_64 com LOAD 0x4000 é verificação
estática; os dois kernels executados continuam 4 KiB.

Um teste opt-in passou no S25 SM-S931B / Android 17 API 37 e no emulador arm64 API
36. Cobre três páginas sintéticas híbridas (texto, scan e rotação), 101 caracteres
com destaque coincidente no raster, oito PDFs inválidos, autenticação adulterada,
inspeção de arquivos novos e marcadores no log do processo. Quatro testes Python
do controlador passaram. Relatórios reais em `validation/results/pdfium-2.0.1-*`;
detalhes, comandos, método de memória e limitações em
[PDF_COMPATIBILITY.md](PDF_COMPATIBILITY.md).


No S25: abertura 104 ms; render 2/2/1 ms; maior amostra PSS 61077 KiB. Esses valores
não são pico garantido, benchmark de corpus nem validação clínica. O primeiro
relatório diagnóstico do emulador foi obtido antes da remoção de INTERNET; os
ensaios finais S25/API36 usam o APK sem a permissão e estão separados.

A tarefa 3.1 **não foi marcada concluída**: auditoria do JNI upstream encontrou
problemas de mutex e de ownership/alocação no caminho direto de bytes. Fonte por
callbacks evita este último caminho, mas o mutex segue pendente. Patch proposto
passou apenas no dry-run; não foi integrado nem testado. A decisão sobre manter
build JNI próprio e a proveniência/avisos do motor pré-compilado seguem abertas.
Progresso permanece **18/51**. Nenhum teste novo de domínio/modelo/corpus completo
foi executado nesta etapa.

## Continuação: correção JNI autorizada

Após autorização do usuário, o patch nativo foi aplicado e recompilado para
quatro ABIs com NDK 27.1.12297006/CMake 3.22.1/API 24. O JNI corrigido com motor
original passou no S25, incluindo 16 aberturas/fechamentos e oito falhas de buffers
com 4 MiB: delta de bytes nativos alocados após as falhas = 0. Entradas nula/vazia
foram rejeitadas. Não é prova de ausência universal de vazamentos nem sanitizer.

Também foi fixado e ensaiado motor PDFium 154.0.8035.0 da distribuição
bblanchon/pdfium-binaries, sem V8/XFA, com hashes, versão, flags e avisos de
terceiros preservados. O build perfil 2 inclui 80 arquivos de avisos/metadados;
o controlador verifica seus hashes e os `.so` no APK antes de instalar. O ensaio
passou no emulador API 36, incluindo 32 ciclos em duas threads. Seis testes Python
passaram. ZIP/ELF foram verificados estaticamente para 16 KiB nos alvos descritos;
os kernels executados eram 4 KiB.

Uma segunda reconstrução offline no mesmo host produziu o mesmo SHA-256 do AAR
perfil 2: `33a6e34cf77773b3284258170ac8275034ff238255e9a185f657964d9f26d91a`.
Os binários gerados permanecem fora do Git, em `pdf-spike/build/`.

O S25 desconectou antes de executar o conjunto final com o motor rastreável;
esta combinação ainda aguarda ensaio físico. Task 3.1 continua pendente,
**18/51**. Detalhes, relatórios e comandos atualizados em
[PDF_COMPATIBILITY.md](PDF_COMPATIBILITY.md). Nenhuma integração PDF em produção
nem validação clínica foi declarada nesta etapa.

Verificação adicional desta etapa: `:app:assembleDebug` passou; inspeção do APK
de produção confirmou ausência de `libpdfium*`. `git diff --check` e validação
estrita do OpenSpec passaram. Nenhum binário nativo foi adicionado ao Git.

## Fechamento do spike PDF no S25 reconectado

O perfil final `pdfium-jni-exames/2` (JNI corrigido + PDFium 154.0.8035.0) passou
no S25 SM-S931B / Android 17 API 37 após reconexão. Relatório preservado em
`validation/results/pdfium-8035-s25-patched-jni-retry.json`. Inclui PDF híbrido e
rotacionado, texto/coordenadas/raster, adulteração, entradas inválidas, 16 ciclos
válidos diretos, 32 ciclos em duas threads e oito falhas de buffers de 4 MiB.
Delta de bytes nativos alocados após falhas: -4320; sem marcadores sintéticos no
log do processo nem arquivos novos após limpeza. É um teste instrumentado
composto, não dezenas de testes independentes.

A tarefa 3.1 foi concluída e o perfil 2 selecionado para integração: **19/51**.
O aplicativo continua sem essa dependência de produção. API 24/kernel 16 KiB,
sanitizers, corpus amplo e fluxo completo seguem pendentes; não há validação
clínica. Detalhes e limites de memória/latência em
[PDF_COMPATIBILITY.md](PDF_COMPATIBILITY.md).

## Integração privada e parser dos layouts — 27/10/2026

O perfil PDFium rastreável passou a integrar `:pdf-local` e o aplicativo.
Texto/raster agora vêm de bytes autenticados da cópia criptografada, com caixas
no raster comum, uma página ativa e fechamento/zeragem explícitos. OCR Latin
empacotado cobre imagens/PDF sem texto, mantendo confiança null. JPEG/PNG têm
as oito orientações EXIF testadas, incluindo espelhamento. Os limites de 20 MiB,
20 páginas e 2048 pixels no maior lado são compartilhados pelos adaptadores.

No S25 SM-S931B / Android 17 API 37 passaram 26 testes instrumentados da fase
PDF/imagem/OCR, incluindo persistência/migração. Ele desconectou antes da suíte
final de limites e parser. Essa suíte final passou no emulador `cc_api36`, API 36:
**30 instrumentados**, zero falhas, incluindo os dois layouts com glifos reais,
limites/rejeição, concorrência/ownership, reabertura protegida e marcadores em
arquivos. Não atribuir esses testes posteriores ao S25.

O parser `ptbr-biochemistry-layouts/1` reconhece os cinco analitos/abreviações nos
templates `simple-v1` e `footnote-v1`, conserva evidências e todas as referências
candidatas no payload criptografado. Contexto literal, colunas concorrentes,
duplicatas, rodapés e conteúdo sem posição têm abstenção explícita. Um teste
com PDF real detectou espaços espúrios dentro de números; a correção usa os
separadores originais do texto, com teste de regressão. Não há consulta ao
gabarito durante o parser. O schema SQL v2 não foi alterado e payloads antigos
sem as novas chaves permanecem legíveis.

Com JDK 21, Kotlin 2.3.21, Hilt 2.58, Room 2.8.5 e AGP 8.13.2, passaram:

```sh
./gradlew assembleDebug test :app:assembleDebugAndroidTest :data-local:connectedDebugAndroidTest
```

Contagem deduplicada: **104 testes JVM**, zero falhas. O APK de produção passou
na conferência dos hashes nativos/notices do motor e no zipalign 16 KiB. O
manifesto continua sem INTERNET e com backup desabilitado. Relatório estruturado:
`validation/results/private-extraction-integration.json`. Procedimentos de
staging/rebuild e limites em [DOCUMENT_EXTRACTION.md](DOCUMENT_EXTRACTION.md);
escopo do parser em [BIOCHEMISTRY_PARSER.md](BIOCHEMISTRY_PARSER.md).

São testes técnicos sobre fixtures sintéticas. Execução integrada em API 24,
kernel 16 KiB, dispositivos de 4 GB, corpus completo, pipeline/UI e inferência
de produção continuam pendentes. O alinhamento de toolchain para PDF não
comprova integração de LiteRT-LM. Nenhuma validação clínica foi realizada.

## S25 reconectado: integração e distribuição offline — 27/10/2026

Os 30 instrumentados de persistência/PDF/OCR/limites/parser passaram também no
S25 SM-S931B / Android 17 API 37. Relatório:
`validation/results/private-extraction-s25-2026-10-27.json`. Isso encerra a
pendência física dessa suíte registrada na etapa anterior, mantendo API 24 e
kernel 16 KiB pendentes para essa combinação integrada.

O módulo foi promovido a `:inference-local`, com contrato JVM, adaptador e Hilt
no app. D8/R8 9.1.31 e `kotlin-metadata-jvm:2.3.21` nos processadores resolveram
as incompatibilidades com metadados Kotlin 2.4 do runtime, mantendo minSdk 24,
SDK 36 e checagens estritas. A API síncrona efetivamente usada está registrada
em `validation/models/selection.json`. O controle de duas Engines do adaptador
passou com os cinco campos corretos: carga 9152/9926 ms e geração 1363/1119 ms.
Memória não foi amostrada nesse controle. Primeira tentativa falhou antes da
carga; repetição após desbloquear passou.

Cinco instrumentados adicionais verificaram preparação atômica, hash, ausência,
corrupção, leitura inválida, truncamento/excesso, cancelamento e concorrência.
Falta de espaço foi simulada por fornecedor injetado, sem encher o telefone.
Quinze testes Python dos controladores/staging passaram.

O APK foi instalado em ID descartável separado, com modo avião/Wi-Fi desligado.
Pesos vieram somente do asset, foram copiados/verificados no diretório privado
e executados pela DI do app. Carga 8386 ms, geração 1302 ms; tempos excluem
staging/cópia/hash. Teste e limpeza passaram, preservando dados do app existente:
`validation/results/gemma4-s25-bundled-offline-2026-10-27.json`. O APK universal
desse ensaio tinha 2142540333 bytes. Modelo e licença foram verificados no APK;
pesos continuam fora do Git. A tarefa **4.9** foi concluída: **25/51**.

O prompt/schema `biochemistry-evidence/1` / `biochemistry-fields/1` e seu validador
independente estão implementados no domínio, com ensaio de dispositivo pronto.
Os testes locais abrangem duplicatas de chaves, truncamento, texto adicional,
instruções maliciosas como dados, referências inventadas, citações da linha
errada, contexto, divergência e ambiguidades anteriores preservadas. O S25 saiu
do USB/ADB antes da primeira instalação (registro
`validation/results/proposal-protocol-preflight-2026-10-27.json`). Após reconexão,
o cache de pesos do harness precisou ser reposto: a suíte instrumentada anterior
havia removido seu pacote. O controlador agora verifica existência/tamanho antes
de reutilizar esse cache. Com os pesos repostos, o modelo gerou em 1236 ms, após
5047 ms de carga, mas a resposta foi rejeitada pelo schema já no primeiro controle.
O segundo não executou. Relatório:
`validation/results/gemma4-s25-proposal-protocol-2026-10-27-provisioned.json`.
A conexão voltou a cair antes do diagnóstico estrutural, que não registra texto.
As tarefas 4.8/4.10/4.11 permanecem abertas, sem ativação na UI ou classificação
direta por saída ainda não validada. Deadline do host usado no ensaio não é
deadline do runtime do app; 4.12 continua pendente.

Build completo, testes JVM e `verifyDexerVersion` passaram após essas mudanças.
O workflow CI foi adaptado para JDK 21 e staging fixado; não foi executado no
serviço remoto. Comandos/resultados/limites em
[BUNDLED_INFERENCE.md](BUNDLED_INFERENCE.md). Nenhuma validação clínica realizada.

## Comparação e métricas enquanto o aparelho está indisponível

Tarefas **6.1 e 6.2** concluídas: **27/51**. Pareamento mutuamente único por
analito/página/evidência, sem valor/faixa esperado como critério. Ausentes,
extras e correspondências não resolvidas ficam explícitos. Comparação bruta e
canônica é separada; o texto integral da referência foi acrescentado ao payload
cifrado opcional para preservar conectores entre faixas. Leitura de payloads
antigos e schema SQL v2 foram mantidos.

As métricas usam contagens/denominadores, N/A vazio, pendentes excluídos, cobertura
sem contribuição de extras e classificação com ausentes no denominador. Anormais
exibidos como dentro são separados de abstenções, ausentes, avaliações faltantes
e direção errada. Um teste calcula explicitamente 4/8 de cobertura, 2/8 de acerto
de classificação, 28/32 campos brutos, fora→dentro 1/7 e não sinalizados 5/7.

Passaram **139 testes JVM distintos**, 15 testes Python dos controladores,
`assembleDebug`, `:app:assembleDebugAndroidTest` e `verifyDexerVersion`. Os 30
instrumentados de persistência/extração passaram novamente no emulador API 36,
incluindo reabertura cifrada do novo referenceRaw e ausência de seu marcador em
claro. Essa rodada posterior ainda não foi repetida no S25. Detalhes em
[VALIDATION_COMPARISON.md](VALIDATION_COMPARISON.md).

A tarefa 6.3 revelou ausência de entradas canônicas completas no contrato do
gabarito. Comparadores, inclusividades, aplicabilidade e ambiguidades não podem
ser inferidos da resposta esperada nem preenchidos pelo parser sob teste sem
misturar os ensaios. Uma proposta concreta de versão 2, preservando v1, está em
[GROUND_TRUTH_V2_PROPOSAL.md](GROUND_TRUTH_V2_PROPOSAL.md). Nenhum gabarito/contrato
canônico novo ou mudança de motivo esperado foi aplicado; 6.3 permanece aberta.

## Gabarito v2 aprovado e medição independente — 27/10/2026

O usuário aprovou `GROUND_TRUTH_V2_PROPOSAL.md`. Implementados schema 1.1,
`cases-v2.json`, gabaritos versão 2 e snapshot canônico explícito. Os arquivos
fonte/schema/renderer v1 continuam byte a byte iguais ao HEAD. Motivos de
unknown-context e competing-reference foram versionados para AMBIGUOUS_INPUT,
preservando NEEDS_REVIEW e todos os motivos v1. O avaliador não foi alterado.

Geração e validação de pacote: **216 documentos / 1080 observações**, com os
**216 documentos idênticos em bytes/hash** à v1 regenerada neste host com os
pins originais. IDs, famílias, partições, textos e valores permaneceram iguais.
A fonte oficial Debian tem o SHA-256 original. O ZIP cloud histórico não estava
disponível para confronto entre hosts; não foi presumida sua igualdade binária.
Pacote local: `validation/generated-v2/corpus.zip`, ignorado pelo Git. Relatório:
`validation/results/ground-truth-v2-2026-10-27.json`.

Os **90 inputs canônicos** dos 18 cenários × cinco analitos passaram em estado
e motivo. Legacy sem snapshot e dados pendentes/inválidos/versões não suportadas
dão N/A explícito no ensaio isolado. Ausência de página não vira página 1. O
harness não chama parser/modelo para construir entradas nem usa respostas
esperadas nessa construção.

No S25, **32 instrumentados** passaram, incluindo PDF privado real → parser →
repositório → medição ponta a ponta e isolada: classificação 5/5 e campos brutos
20/20. Nova versão alterando apenas um valor do gabarito produziu comparação
4/5 sobre a mesma extração, mantendo 5/5 no ensaio canônico e preservando todas
as saídas/comparações originais após reabrir o banco.

Build completo, **147 testes JVM distintos**, **22 testes Python de corpus** e
`verifyDexerVersion` passaram. Comando:

```sh
./gradlew assembleDebug test :app:assembleDebugAndroidTest :data-local:connectedDebugAndroidTest verifyDexerVersion
```

A tarefa **6.3** foi concluída: **28/51**. São controles técnicos sintéticos,
sem validação clínica ou benchmark ponta a ponta de todo o corpus. Relatórios
agrupados/UI, pipeline recuperável e aceitação do novo prompt de inferência
continuam pendentes.

## Relatórios locais por estrato — 27/10/2026

Tarefa **6.4 concluída: 29/51**. `GenerateLocalValidationReport` lê execuções e
SHAs de gabarito explícitos no repositório protegido. Agregação por origem,
formato, analito, layout/versão e partição preserva denominadores, pendências,
abstenções, extras e famílias; versões/modos e saídas automáticas/corrigidas
ficam em grupos separados. Frações vazias são N/A, sem cobertura pública
presumida. Contrato e limites em `docs/VALIDATION_COMPARISON.md`.

Passaram `./gradlew test assembleDebug :app:assembleDebugAndroidTest`, com
**155 testes JVM distintos**, incluindo oito de relatório. O teste instrumentado
`MeasurementIntegrationTest` passou no emulador API 36: extração de PDF privado,
reabertura protegida e relatório com 5/5 para v2 e 4/5 para v3 em grupos separados,
mantendo saídas originais. Não é medição do corpus completo nem validação clínica.
Evidência: `validation/results/local-reports-2026-10-27.json`.

O novo ensaio do protocolo de inferência no S25 parou na guarda de tela bloqueada
antes de carregar o modelo (sem eventos de carga/geração); depois o aparelho
deixou de aparecer no USB/ADB. O relatório técnico é
`validation/results/gemma4-s25-protocol-shape-after-v2-2026-10-27.json`.
Essa tentativa não mede qualidade do modelo nem substitui o ensaio de schema
anterior. Aceitação do protocolo continua pendente de ensaio com tela desbloqueada.
Tela/exportação de relatórios, autoria de correções na UI, métricas de recursos
do pipeline e A/B continuam nas tarefas seguintes.

## Validação de propostas antes da avaliação — 27/10/2026

Por orientação do usuário, os próximos ensaios no S25 ficam para a etapa final;
o desenvolvimento continua com testes JVM e emulador, sem aceitar esses testes
como substitutos de inferência real no aparelho de referência.

Tarefa **4.11 concluída: 30/51**. `ProcessBiochemistryPage`, fornecido por Hilt,
conecta texto/fragmentos → parser → inferência opcional → schema/evidência →
avaliador determinístico. BASELINE não chama inferência. Propostas sem suporte,
divergentes ou da linha errada preservam a fonte e exigem revisão; a concordância
do modelo não apaga ambiguidades. A citação verifica texto, região, método,
versão de extrator e confiança, além do ID. O caso de uso não retém texto gerado
nem mensagens de exceção do backend. Detalhes em `docs/BUNDLED_INFERENCE.md`.

Passaram **166 testes JVM distintos**, `assembleDebug`,
`:app:assembleDebugAndroidTest` e os **dois testes de `PrivateParserTest` no
emulador API 36**. O novo teste usa texto PDF real e resposta simulada sem
suporte, verificando cinco NEEDS_REVIEW antes da avaliação. Nove testes novos
do caso de uso e dois de vínculo da fonte cobrem falhas, limites e cancelamento.
Evidência: `validation/results/page-evidence-boundary-2026-10-27.json`.

Não houve execução nova no S25 ou comprovação de extração pelo Gemma nesta etapa.
O novo protocolo real (4.10), deadlines/isolamento JNI (4.12), rastreabilidade
persistida (4.13), pipeline/UI e A/B permanecem pendentes. A bancada assistida
ainda não foi ativada; sucesso de teste com backend simulado não é sucesso de IA.

## Worker e retomada por página — 27/10/2026

Tarefa **5.1 concluída: 31/51**. `ProcessingWorker` recebe somente runId opaco,
usa trabalho único KEEP, sem constraint de rede, e resolve dependências por Hilt.
`ResumeProcessingRun` pula páginas COMPLETE e confirma lotes/checkpoints pela
mesma transação protegida. Quantidade de páginas é resolvida sobre a cópia
privada; modo/configuração não são misturados. Checkpoints novos identificam
participação de IA, sem converter registros legados desconhecidos em sucesso.

Passaram build/debug, APK de testes do app e **174 testes JVM distintos**,
incluindo oito de retomada. Dois instrumentados no emulador API 36 verificaram
WorkData/constraints e worker sobre PDF privado de duas páginas. Também passou
o ensaio opt-in de preparação/force-stop/retomada em processos distintos
(PIDs 12929 e 12980): cinco observações da primeira página preservadas, dez
após concluir a segunda, sem duplicação. A interrupção após commit foi injetada;
o encerramento/reinício entre instrumentações foi real, com fonte/base cifradas.
Não comprova SIGKILL dentro de transação nem execução durante force-stop.

Evidência em `validation/results/worker-pipeline-2026-10-27.json` e
`validation/results/worker-process-recovery-emulator-2026-10-27.json`;
reprodução/limites em `docs/PROCESSING_PIPELINE.md`. O pacote de testes criado
pelo controlador, marcador, fonte, base e chave sintéticas foram removidos.

O adaptador de produção aceita agendamento BASELINE nesta etapa. Suporte do
executor aos dois modos foi testado com portas controladas; liberação do
agendamento assistido depende dos gates 4.12/4.13 e não foi substituída por
fallback. Cancelamento explícito, retentativa em execução filha e reconciliação
de órfãos continuam em 5.2, exclusão concorrente em 5.3–5.5 e UI em 7.1.
Não houve teste novo no S25: permanece reservado para a etapa final conforme
orientação do usuário. Não é validação clínica nem medição do corpus completo.

## Cancelamento, retentativa e reconciliação — 27/10/2026

Tarefa **5.2 concluída: 32/51**. `ManageProcessingRuns` confirma cancelamento
transacional antes de parar WorkManager, preserva estados terminais e cria
retentativas em execuções filhas com IDs novos/idempotentes, sem alterar saídas
originais. A reconciliação na abertura e periódica recupera execuções gravadas
antes de serem agendadas, cancela trabalhos de execuções removidas/terminais e
identifica configurações incompatíveis. Falha global após três tentativas recebe
código enum fixo, sem corpo de exceção; indisponibilidade da chave/base mantém
retentativa sem recriação silenciosa. Detalhes em `docs/PROCESSING_PIPELINE.md`.

PDF sintético com stream inválido na página 2 produziu PARTIAL, com dez
observações válidas das páginas 1/3 e checkpoint intermediário FAILED. A filha
reprocessou a mesma fonte, preservando a execução anterior e sem fabricar reparo.
Páginas sem layout/observações suportadas agora falham explicitamente; warnings
operacionais ficam no checkpoint cifrado. NEEDS_REVIEW/NOT_CLASSIFIABLE continuam
resultados válidos, não falhas técnicas.

Passaram `test assembleDebug :app:assembleDebugAndroidTest`: **181 testes JVM
distintos**, incluindo sete de controles. Os quatro instrumentados novos passaram
no emulador API 36 com PDF real, reabertura da base, commit tardio rejeitado,
reconciliação/cancelamento no WorkManager real e falha global durável.
A regressão completa de `:data-local:connectedDebugAndroidTest` também passou:
**39 aprovados e um ignorado opt-in** (ensaio de morte de processo em duas
invocações, já executado separadamente em 5.1). Os 40 casos são distintos; o
contador textual do runner mostrou 41 ao finalizar, mas o XML registra 40,
zero falhas/erros e um skipped. Evidência:
`validation/results/processing-controls-2026-10-27.json`.

Não houve novo teste no S25, mantido para o final. Exclusão coordenada/limpeza,
UI dos controles, deadlines do runtime e aceitação do protocolo real continuam
pendentes. Os resultados são técnicos e sintéticos, sem validação clínica.

## Exclusão coordenada e barreiras permanentes — 27/10/2026

Tarefa **5.3 concluída: 33/51**. A transação de exclusão agora grava tombstone
permanente, outbox e IDs de trabalhos antes da cascade. O cancelamento é tentado
após ocultar; a limpeza só confirma a outbox depois de cancelar, estabelecer
barreira durável nos arquivos e remover o diretório. Escritores SQL e de arquivos
continuam bloqueados depois do ack, inclusive após reabertura. Falha de
cancelamento ou interrupção após apagar arquivos preserva retentativa.

Room passou a **3**, por migração explícita 2→3; AAD/payloads permanecem na
versão 2. O teste com identidade exportada de v2 validou a migração, preservou
o ciphertext documental e os registros existentes, e criou tombstones para
outboxes conhecidas. A migração recuperável de v1 e seus pontos de interrupção
continuam aprovados. Os schemas 2/3 foram preservados/exportados, sem fallback
destrutivo ou reescrita dos payloads existentes na atualização 2→3.

Passaram `test assembleDebug :app:assembleDebugAndroidTest` e a regressão completa
de `:data-local:connectedDebugAndroidTest` no emulador API 36: **181 JVM distintos,
46 instrumentados aprovados e um opt-in ignorado**. Sete testes novos verificam
página em andamento, inferência simulada aguardando final, escritor concorrente,
falha de cancelamento, recuperação da limpeza, migração e marcador inválido.
Após exclusão e ack, callbacks não recriaram documento, campos ou arquivos.

Evidência: `validation/results/deletion-coordination-2026-10-27.json`.
Garantias, retenção apenas de IDs operacionais/byte de exclusão e limites em
`docs/DELETION_COORDINATION.md`. A auditoria completa de derivados, a UI e os
deadlines/isolamento de inferência continuam nas tarefas correspondentes.
Não houve execução nova no S25 ou com modelo real; os ensaios físicos ficam
para o final. Não comprova apagamento físico de flash ou interrupção imediata
de JNI/OCR e não constitui validação clínica.

## Auditoria de derivados e procedimentos de recuperação — 27/10/2026

Tarefas **5.4 e 5.5 concluídas: 35/51**. Inventário dos destinos clínicos,
retenções operacionais e testes em `docs/DERIVATIVE_AUDIT.md`; estados e
procedimentos de falha, retomada, force-stop e chave perdida em
`docs/PROCESSING_RECOVERY.md`.

A fixture de auditoria incluiu original, prévia/OCR/traces cifrados, arquivo
parcial, duas execuções, observação/evidência/candidatos, referência, avaliação,
checkpoint, correção, gabarito e comparações dos dois tipos. Após exclusão/ack,
as relações/diretório estavam ausentes e oito categorias de escritor tardio
foram rejeitadas. Outro documento/artefato e pesos sintéticos compartilhados
foram preservados. Canário dinâmico não apareceu em claro nos diretórios privados,
caches e bancos/journals observados antes/depois.

A auditoria encontrou e corrigiu recriação da chave de artefatos em leitura:
chave ausente agora retorna erro fixo sem criar alias. Nova escrita também não
cria chave se existir ciphertext inacessível. A criação inicial é serializada
por lock entre instâncias/processos; duas instâncias concorrentes gravaram e
reabriram corretamente seus arquivos no teste. Os testes usam raízes/aliases
isolados, sem invalidar chaves do app do usuário. Perda da chave de payload
preservou banco, fonte cifrada e outbox pendente sem recriar base vazia.

Passaram `test assembleDebug :app:assembleDebugAndroidTest` e regressão completa
de `:data-local:connectedDebugAndroidTest`: **181 JVM distintos, 50 instrumentados
aprovados e um opt-in ignorado** no emulador API 36. A retomada opt-in foi executada
separadamente após a correção, em PIDs **15596/15646**, sem reinstalar entre fases,
preservando cinco observações da página 1 e concluindo dez sem duplicação.
O controlador removeu somente o pacote de testes criado por ele.

Evidência: `validation/results/derivative-audit-and-recovery-2026-10-27.json` e
`validation/results/worker-process-recovery-keyguard-emulator-2026-10-27.json`.
Prévia/OCR de produção continuam em memória; a auditoria de derivados persistidos
usa o store protegido. Não houve inferência real nova, auditoria de cache de
driver GPU ou execução no S25. Não promete trabalho durante force-stop,
apagamento físico de flash ou interrupção imediata de JNI/OCR. Runtime limitado,
rastreabilidade assistida e UI continuam pendentes nas tarefas correspondentes.

## Processo dedicado de inferência — etapa parcial de 4.12

Implementados serviço privado `:inference`, handshake com nonce/UID/PID, uma
solicitação global sem fila, prazos de carga/geração de 120 s cada, encerramento
do filho e espera de morte do Binder antes de nova chamada. Cancelamento não
espera cooperação do thread de geração. O processo filho prepara/verifica os
pesos e cria Engine nova; sucesso/falha também descartam o processo. Falha em
confirmar encerramento bloqueia novas chamadas até reiniciar o processo
principal. BASELINE retorna sem binding/preparação. Manutenção de banco/trabalhos
é iniciada somente no processo principal.

Quotas novas: instrução/documento até 16384 bytes UTF-8 combinados e canal final
até 16384 caracteres, com rejeição explícita sem truncar. Isso é limite de
IPC/memória, **não estimativa de tokens**. Engine continua com contexto/saída
2048/1024. Falta aceitar orçamento/segmentação de documentos e contexto/truncamento
com o modelo real; API inspecionada fornece contagem de KV após processamento,
não um tokenizer público de entrada. Detalhes/fontes em `docs/INFERENCE_PROCESS.md`.

Passaram build/debug, APK de teste do app, **183 testes JVM distintos** e a suíte
de `:inference-local:connectedDebugAndroidTest` no emulador API 36: **16 aprovados,
três opt-in de modelo real ignorados**. Sete controles novos incluem seis com
serviço simulado (carga/geração bloqueadas, cancelamento, crash, concorrência,
prefixos iguais em PIDs diferentes, quotas e serviços privados) e um de serviço
de produção sem asset, retornando MODEL_UNAVAILABLE antes de carregar o motor.
Preparação de arquivos/corrupção permanece coberta nos testes próprios. Nenhum
teste novo desta etapa carregou Gemma ou executou no S25.

Evidência: `validation/results/inference-process-controls-2026-10-27.json`;
configuração em `validation/models/selection.json`. **4.12 permanece aberta e o
progresso continua 35/51**: controles simulados não concluem aceite de JNI,
contexto/truncamento ou ShortConv/KV do Gemma real neste novo caminho. Resultados
S25 anteriores são históricos do adaptador anterior; repetição fica na etapa
final conforme orientação do usuário. Bancada assistida continua bloqueada nos
gates correspondentes; não é conclusão por stub/fallback exclusivo.

### Montagem limitada de prompts — 30/10/2026

A quota combinada de 16384 bytes UTF-8 agora vale durante a construção do prompt,
incluindo instrução, delimitadores e JSON escapado. O texto bruto é limitado
antes do escaping; a lista admite no máximo 16384 fragmentos, incluindo vazios.
Excesso gera `INPUT_LIMIT` antes de chamar o backend e conserva a saída completa
do parser e suas ambiguidades, sem truncar evidência ou contar sucesso de IA.

Passaram `test assembleDebug :app:assembleDebugAndroidTest`: **188 testes JVM
distintos, 171 no domínio**, incluindo cinco novos testes de fronteira, UTF-8,
expansão por escaping, fragmentação e fallback. Build e validação estrita OpenSpec
passaram. Nenhum instrumentado/modelo/S25 foi executado nesta etapa. Relatório:
`validation/results/prompt-construction-bounds-2026-10-30.json`.

Isso fecha a alocação ilimitada na montagem, não o orçamento nativo de tokens.
Segmentação, contexto excedido e truncamento real continuam pendentes em
4.10/4.12; progresso permanece **35/51**, com S25 reservado para a etapa final.

### Diário protegido de inferência — 30/10/2026

Implementada a rastreabilidade de 4.13: configuração atestada comparada ao run,
intenção cifrada anterior à chamada, resultado/fallback/tempos disponíveis e
histórico preservado no checkpoint. Intenções inconclusas permanecem após
reabertura; cancelamento fecha intenções sem publicar a página, inclusive após
retorno do modelo e antes do commit. Não armazena prompt, final, pensamento ou
texto de exceção. Novos campos são opcionais para leitura do legado; Room 3
permanece estruturalmente igual. Detalhes em `INFERENCE_TRACE.md`.

Passaram build/debug/APK de testes, **197 testes JVM distintos (177 no domínio)**
e **57 instrumentados aprovados no emulador API 36**, com um opt-in de recuperação
em duas invocações ignorado. Sete controles novos de inferência usam PDF privado
real e adaptador simulado. A auditoria de exclusão inclui resumo/diário cifrados
e canário dinâmico, com pesos de controle preservados. Teste JVM também confere
coordenada e SHA-256 do AAR LiteRT-LM efetivamente resolvido pelo Gradle.

Relatório: `validation/results/inference-trace-emulator-2026-10-30.json`.
Isso conclui 4.13, elevando o progresso para **36/51**. Não houve inferência real,
novo force-stop nem teste no S25 nesta etapa. 4.8, 4.10 e 4.12 continuam pendentes;
a bancada assistida segue bloqueada até aceitar protocolo e limites reais.

### Bancada: importação, histórico, reabertura e exclusão — 31/10/2026

O fluxo individual sem IA agora usa o importador protegido, execução imutável
antes do agendamento, WorkManager e estados Compose/StateFlow. Histórico e
resultados reabrem fonte privada, campos/avaliação original, correções, gabaritos
e comparações salvos. A confirmação de exclusão chama a barreira/outbox existente;
remoção e limpeza não dependem da URI de origem. Fluxo em `WORKBENCH_FLOW.md`.

Passaram navegação e o controle em duas fases no emulador API 36: prepare no
PID **2795**, force-stop e resume no PID **2872**. Cinco observações, uma correção,
um gabarito e uma comparação foram reabertos com acesso externo revogado. A
prévia autenticada foi exibida. O Bundle foi inspecionado durante recriação real
da Activity sem marcadores clínicos/URI ou bitmap. Cancelar a confirmação manteve
o documento; confirmar removeu dados observados e original, preservando sentinela
de pesos. Nenhum modelo foi executado. O callback do seletor foi chamado
diretamente; DocumentsUI não foi automatizado.

Relatório: `validation/results/workbench-reopen-emulator-2026-10-31.json`.
Limpeza somente dos pacotes criados nesta sessão:
`validation/results/workbench-session-cleanup-2026-10-31.json`. O app existente
não foi alvo do ensaio ou da desinstalação.

Na conferência final de 01/11/2026, passaram `test assembleDebug
:app:assembleDebugAndroidTest` com applicationId padrão e **213 testes JVM
distintos (184 no domínio)**. Os 16 testes novos cobrem criação/agendamento,
configuração/identidade, duplicação, cancelamento, indisponibilidade, estados
de UI, rotas e prévia tardia após exclusão. Esse total não representa nova suíte
completa de instrumentados: foram executados os controles Compose descritos.

Isso conclui **7.4** e eleva o progresso para **37/51**. Pacotes (7.1), destaque
e revisão completa (7.2), edição/reavaliação (7.3), relatórios/acessibilidade (7.5),
estados assistidos (7.6) e aceite real de IA continuam pendentes. O S25 permanece
reservado para a etapa final. Esta evidência é técnica, sintética e não clínica.

### Evidência destacada e revisão documental separada — 03/11/2026

Implementada a 7.2: campos/motivos, estado documental independente da avaliação,
estado esperado/motivo do gabarito e seleção de regiões por campo/página. A prévia
identifica origem extraída ou versão do gabarito; coordenadas já orientadas são
normalizadas sem nova rotação. Geometria incompatível, página indisponível ou
perda de precisão não produz destaque estimado. Referências legadas da tabela
própria e candidatos sem seleção são preservados sem inventar regiões.

Diferenças salvas mostram valores esperado/obtido e acesso à evidência quando
hash, versões e pareamento são compatíveis. Não há seleção por valor esperado
ou preferência silenciosa entre linhas duplicadas. Abrir fonte ou registrar
revisão não modifica o resultado automático, NEEDS_REVIEW ou NOT_CLASSIFIABLE.

Passaram build padrão e **223 testes JVM distintos (184 no domínio)**. Quatro
controles Compose no emulador API 36 passaram sem falhas/ignorados, incluindo
pixels do contorno em quatro orientações, frame incompatível, revisão/abstenção,
gabarito pendente e referência legada com avaliação original preservada. O fluxo
com PDF privado real passou em PIDs **15683/15751**, após force-stop e URI revogada,
com destaque extraído/gabarito, saved state verificado e exclusão. Pacotes criados
pelo ensaio foram removidos; S25 e modelo real não foram executados.

Evidência: `validation/results/evidence-review-emulator-2026-11-03.json` e
`validation/results/workbench-evidence-final-emulator-2026-11-03.json`.
Procedimento/limites em `EVIDENCE_REVIEW.md`. O total JVM não representa uma nova
suíte completa de instrumentados; foram executados os controles descritos.

Isso conclui **7.2** e eleva o progresso para **38/51**. Edição/reavaliação (7.3),
pacotes (7.1), relatórios/acessibilidade (7.5), estados assistidos (7.6) e aceite
real de IA continuam pendentes. Esta etapa não estabelece validação clínica.


### Correção, revisão de gabarito e novas saídas — 03/11/2026

Implementada a **7.3**, com ações distintas na UI Hilt/Compose: correção append-only
com antes/depois, reavaliação vinculada (`CORRECTED`/`REASSESSED`), nova extração
explicitamente sem IA (`AUTOMATIC`), revisão independente do contrato de gabarito
em outra versão/SHA e comparação com hash revisado explícito. Gabaritos pendentes
não podem gerar comparações de acerto. Reavaliar não executa OCR/IA nem fabrica
latência/participação; mantém ambiguidades da origem e páginas incompletas.
Os relatórios separam bases e rejeitam base/IDs incompatíveis com a execução.

Passaram `test assembleDebug :app:assembleDebugAndroidTest` com applicationId
padrão e **245 testes JVM distintos (200 no domínio)**. Os 22 novos testes cobrem
projeção/replay/limites/fallbacks, autoria independente e limites JSON, concorrência
lógica, rascunhos, separação de ações e gabarito pendente. No emulador API 36,
passaram **11 testes instrumentados** selecionados de `ReviewWorkflowTest`,
`ProtectedRepositoryTest` e `MeasurementIntegrationTest`, sem falhas/ignorados.
Incluem transação/rollback completo, snapshot obsoleto, reabertura do banco,
exclusão e métricas originais preservadas. Não é uma nova suíte completa de todos
os instrumentados.

O controle Compose real passou com prepare no PID **18013**, force-stop e resume
no PID **18081**. Após revogar a fonte externa, reabriu cinco observações e suas
evidências. Corrigiu um campo pela UI, criou reavaliação filha, editou uma nova
versão de gabarito e registrou revisor/data explicitamente antes de comparar.
Uma nova extração sem IA preservou o valor extraído da fonte; observações,
avaliações e comparações originais permaneceram iguais por identidade/conteúdo.
A Activity foi recriada com JSON de gabarito e rascunho clínico abertos: nenhum
marcador de conteúdo/URI/bitmap entrou no Bundle e os rascunhos foram descartados
pela reabertura da rota. A exclusão removeu derivados e preservou a sentinela de pesos.

Relatórios finais: `validation/results/review-workflow-emulator-2026-11-03.json`
e `validation/results/workbench-review-verified-emulator-2026-11-03.json`.
Os dois ensaios intermediários com falha foram preservados: expectativa errada de
comparação de gabarito pendente e asserção dependente da ordem SQL, ambas corrigidas.
A limpeza removeu somente os pacotes sintéticos criados nesta sessão; registro em
`validation/results/workbench-review-cleanup-2026-11-03.json`. O app padrão não foi
alvo de instalação/desinstalação. Procedimento e limites em `REVIEW_WORKFLOW.md`.

Isso conclui **7.3** e eleva o progresso para **39/51**. Pacotes (7.1), relatórios/
acessibilidade (7.5), ativação assistida (7.6), gates de inferência e aceite final
continuam pendentes. Nenhum modelo ou S25 foi executado nesta etapa; não houve
benchmark ou validação clínica. O S25 segue reservado para o final.


## Pacotes, relatório acessível e medições — 03/11/2026

A importação ZIP 1.0/1.1 está conectada ao armazenamento protegido e WorkManager.
O ensaio descartável importou oito casos sintéticos, quatro formatos/dois layouts,
revogou a fonte externa e verificou relatório, TalkBack ativo, fonte 200%, foco em
título, alvos de 48 dp, rolagem e exclusão pelo ViewModel Hilt. Configurações de
acessibilidade foram restauradas; somente os pacotes criados foram removidos.
Evidência: `validation/results/corpus-report-a11y-verified-emulator-2026-11-03.json`.

O OCR atual é Tesseract local com português fixado; ML Kit/DataTransport foram
removidos da árvore de produção. Ver `OCR_COMPATIBILITY.md` para licença, staging,
privacidade e versões. O caso exibido no relatório teve 0/5 classificações corretas
e 2/5 de cobertura numérica: aprovação do fluxo não significa aprovação da
qualidade da extração. Layout desconhecido continua falhando explicitamente.

Controles executados nesta etapa:

- 265 testes JVM distintos nos relatórios locais mais recentes, todos aprovados.
- 22 regressões Python do corpus aprovadas com Python 3.14 e fonte DejaVu fixada.
- Suíte `:data-local:connectedDebugAndroidTest`: 62 testes, zero falhas/erros,
  um ignorado (recuperação entre invocações exige o controlador em fases).
- 16 controles instrumentados selecionados de inferência aprovados em API 36:
  medições numéricas, preparação/hash, JSON final e ciclo de vida IPC. O serviço
  simulado cobre travamento, queda, timeout, cancelamento e prefixos iguais;
  isso não comprova o novo caminho nativo Gemma no S25.
- Testes finais de protocolo real e instalação/A-B/revisão/reinício/exclusão
  compilados, ainda não executados no S25 nesta etapa. CI adaptada, sem execução
  remota comprovada.

`CORPUS_IMPORT.md` descreve o fluxo do revisor. `PERFORMANCE_MEASUREMENTS.md`
distingue duração monotônica, PSS amostrado, endpoints, memória disponível e
contagens agregadas do SDK. Tokens thought/final separados são indisponíveis e
permanecem nulos; não há armazenamento/inspeção de raciocínio. Pares automáticos
exigem fonte/gabarito/regra idênticos e não contam sucesso do parser como uso
aceito do modelo. Hardware de 4 GB, kernel 16 KiB e memória GPU completa continuam
pendentes. O aceite de produção permanece desativado até o ensaio real passar.

Build final desta etapa: `./gradlew assembleDebug test :app:assembleDebugAndroidTest
--no-daemon --console=plain`, aprovado, 676 tarefas Gradle. O APK padrão foi
verificado pelo `tools/verify_android_artifact.py` com a árvore efetiva
`:app:dependencies --configuration debugRuntimeClasspath`, manifest mesclado e
aapt; passou também `zipalign -c -P 16 4`. Pesos/licenças, ausência de INTERNET,
backup/cleartext desabilitados, minSdk 24 e ausência de clientes de rede/telemetria
foram conferidos. Os 15 testes host de `tools/litert` passaram. Evidência e hashes:
`validation/results/final-build-artifact-audit-2026-11-03.json`.
Isso não comprova kernel de 16 KiB nem execução real do novo protocolo.

Os comandos dos dois ensaios físicos e seus critérios estão em
[FINAL_ACCEPTANCE.md](FINAL_ACCEPTANCE.md). O S25 não está listado pelo ADB nesta
etapa; somente o emulador. CI ainda não executada remotamente. Não concluir a
bancada nem o aceite de IA até os controles reais passarem.

## S25 disponível: protocolo nativo e A/B reprovado — 03/11/2026

O S25 SM-S931B/Android 17/API 37 voltou ao USB. Antes da instalação, modo avião
foi ativado e Wi-Fi desligado temporariamente (estado anterior: avião desligado,
Wi-Fi ligado; restauração deve ocorrer ao terminar). O pacote padrão foi preservado.

O primeiro ensaio parou no Espresso antigo antes de gerar; Espresso core 3.7.0
somente em androidTest corrigiu a reflexão removida no Android 17. O controle
nativo v3 passou em instalação limpa offline: duas linhas com spans aceitos,
PIDs distintos, entrada maliciosa protegida, excesso de bytes/contexto rejeitado
e processo nativo aposentado após cancelamento. Evidência:
`native-production-s25-2026-11-03-protocol3.json`, detalhes em `LITERT_COMPATIBILITY.md`.

O A/B completo v3 teve oito pares e zero páginas aceitas pelo modelo, portanto
`final-workbench-s25-2026-11-03.json` está reprovado. As fases posteriores desse
controlador não foram executadas. Schema inválido, omissão de referências,
divergências e associação incorreta permaneceram em fallback. O resultado das duas
linhas não aprova o documento completo. Diagnósticos de forma/igualdade v3/v4 foram
preservados sem texto fonte/final ou raciocínio.

O prompt atual v5 usa apresentação literal agrupada com aliases e posições;
schema, validação de evidência e avaliador não foram relaxados. O sinal de aceite
foi desativado. A suíte completa passou com 269 testes JVM distintos; os dois
novos testes Python do seletor de partições também passaram. Auditoria do APK v5
passou (modelos/licenças/hash, sem INTERNET/backup/clientes de rede/telemetria).
O ensaio físico v5 continua em diagnóstico, sem aceite declarado.

O seletor inicial expôs oito casos HELD_OUT no ajuste de prompt; ver registro de
famílias em `validation/corpus/exposed-held-out-families-2026-11-03.json` e
`CORPUS.md`. Os próximos controles usam exclusivamente DEVELOPMENT, sem mudar
identidades/gabaritos/rótulos originais. Não apresentar as famílias expostas como
hold-out independente. Os resultados são técnicos, sem validação clínica.

## Aceite completo v5 concluído no S25 — 03/11/2026

Passaram os controles nativos v5 e a rodada limpa completa
`final-workbench-s25-2026-11-03-protocol5-development-verified.json`. Foram oito
pares DEVELOPMENT (quatro formatos/dois layouts), preparação e seleção dos modos
pela UI, uso real do modelo, correção com métricas automáticas imutáveis, force-stop
(PIDs 7134/8294), reabertura privada sem URI externa e exclusão recuperável dos
oito documentos. Pesos estáticos permaneceram íntegros antes de remover somente
os pacotes descartáveis criados pelo controlador. O app padrão foi preservado.
Modo avião e Wi-Fi foram restaurados ao estado anterior (0/1).

Oito páginas assistidas produziram **uma página aceita e sete fallbacks**. A maioria
da amostra ainda não satisfaz schema/evidência/modelo; não confundir aceite do
fluxo com aprovação da qualidade da extração. Os relatórios anteriores reprovados
permanecem disponíveis. A espera do teste após salvar correção foi corrigida para
exigir recarga completa e a correção persistida, sem mudar modelo, corpus ou
critérios. Resultados por formato/layout, novas divergências, latências, memória
e medições indisponíveis: [S25_FINAL_VALIDATION.md](S25_FINAL_VALIDATION.md).

O sinal de aceite v5 está ativo somente no S25 de referência. Hardware de 4 GB,
execução em kernel de 16 KiB, contabilização completa de memória GPU e contagens
separadas thought/final continuam sem comprovação. CI foi adaptada, mas não houve
execução remota comprovada. Não há validação clínica ou teste de generalização
independente sobre famílias HELD_OUT expostas.

## Fechamento da implementação — 51/51

OpenSpec `add-android-biochemistry-validation`: 51/51 tarefas concluídas,
`state=all_done`, validação estrita aprovada. A mudança permanece disponível para
revisão/arquivamento; os artefatos e as tentativas reprovadas foram preservados.

Build padrão final aprovado:

```bash
./gradlew assembleDebug test :app:assembleDebugAndroidTest --no-daemon --console=plain
./gradlew :app:dependencies --configuration debugRuntimeClasspath --no-daemon --console=plain
```

Foram aprovados 269 testes JVM distintos, 24 testes Python do corpus e 15 controles
host LiteRT. Os 16 instrumentados de runtime selecionados passaram no emulador;
a suíte de dados teve 62 testes, zero falhas/erros e um ignorado, descrito acima.
Os controles físicos de protocolo e fluxo completo passaram no S25 conforme os
relatórios vinculados. O teste do fluxo completo inclui duas invocações da mesma
classe em processos distintos; não são contadas como dois testes distintos.

APK padrão: `app/build/outputs/apk/debug/app-debug.apk`, applicationId
`br.com.exames.offline`, 2129770549 bytes, SHA-256
`91549d1709a373207b30356c389158dc6a5524c2a95bd4efb57a5873be7246bb`.
Pesos permanecem fora do Git e são empacotados somente no build, fixados por hash.
Não houve instalação/atualização desse APK no app padrão existente do usuário.

Auditoria final aprovada: modelos/licenças/hashes e assets sem compressão,
minSdk 24, sem INTERNET, backup/cleartext desabilitados, sem clientes de rede ou
telemetria na árvore efetiva, alinhamento ZIP de 16 KiB e `git diff --check`.
Evidência consolidada:
`validation/results/final-standard-build-audit-s25-2026-11-03.json`.
As limitações de qualidade e de hardware, medições indisponíveis e ausência de
validação clínica continuam explícitas em `S25_FINAL_VALIDATION.md`.
