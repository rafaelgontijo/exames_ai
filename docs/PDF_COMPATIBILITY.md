# Compatibilidade e spike PDF

Data: 27/10/2026. Tarefa OpenSpec 3.1 continua pendente. Na investigação inicial
do PDFBox, nenhuma biblioteca nova foi adicionada ou executada. O ensaio PDFium
autorizado depois está registrado abaixo, isolado do APK do aplicativo. O
adaptador de produção atual ainda depende de URI externa e não conclui o contrato.

## Candidato inspecionado

PDFBox-Android `com.tom-roush:pdfbox-android:2.0.27.0`, indicado pelo README oficial
como port Android de PDFBox, Apache-2.0, API 19+ para funcionalidade completa.
Isso não comprova execução API 24, ABI/16 KiB, memória nem adequação de produção.
A fonte publicada foi baixada diretamente do Maven Central:

- [Sources JAR 2.0.27.0](https://repo.maven.apache.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/pdfbox-android-2.0.27.0-sources.jar).
- SHA-256 real: `340b7e30afa2dc52af2e84c729aba5422eb34302006c9be48a4993e89b1a7f85`.
- [README do projeto](https://github.com/TomRoush/PdfBox-Android).

A implementação `PDDocument.load(byte[], String, InputStream, String,
MemoryUsageSetting)` permite entrada em memória e política de scratch somente em
memória. Isso é um caminho candidato, não evidência de ausência de todos os
caches, alocação limitada de imagens ou logs seguros.

## Bloqueio de confidencialidade

A fonte publicada contém chamadas diretas a `android.util.Log`, incluindo:

- `com/tom_roush/pdfbox/pdfparser/BaseParser.java:933`: aviso concatena `badString`,
  texto inesperado consumido durante o parse do documento.
- `com/tom_roush/pdfbox/pdfparser/COSParser.java:970`: aviso concatena
  `endObjectKey`, token lido do documento após um objeto malformado.

Logo, capturar/sanitizar exceções no adaptador não impede esses logs internos.
O bloqueio vem do código da biblioteca, não de uma alegação de vazamento observado
em produção: não houve execução com conteúdo clínico. O corpus sintético também
não autoriza deixar esse caminho habilitado para documentos reais.

A governança proíbe conteúdo de exames em logs de produção. Antes de promover
esse candidato, decidir entre manter uma variante auditada com logging de
conteúdo removido ou avaliar outro extrator de texto. Uma variante própria
acrescenta manutenção de código de terceiros, avisos/licença e atualizações de
segurança; a escolha precisa ser refletida no desenho antes da implementação.
Não alterar a regra de privacidade para aceitar o comportamento.

Depois dessa decisão, ainda executar o spike autenticado texto/raster, PDF
híbrido/rotacionado, inspeção de caches/logs, memória, API/ABI/16 KiB e licença.
O minSdk 24 autorizado permanece inalterado.

## Caminho PDFium autorizado e executado

Após autorização do usuário para seguir com PDFium, foi criado `:pdf-spike`,
apenas com Java e dependências `androidTestImplementation`. O aplicativo não
depende desse módulo nem desses AARs. Não é integração de produção.

Candidato: `io.legere:pdfiumandroid:2.0.1`, módulos api/core da mesma versão.
Tag `2.0.1`, commit `6ae0ef0abb8835d97bf93b0f10494cbbf58d7b38`.
AARs e sources JARs publicados no Maven Central têm hashes registrados em
`validation/pdf/pdfium-candidate.lock.json`, junto dos hashes de cada `.so`.

A versão 2.0.3 declara minCompileSdk 37 e não foi adotada. A versão 2.0.1 declara
minCompileSdk 36/minSdk 24, classes Java 21 e metadados Kotlin 2.3. O ensaio
compilou com JDK 21, AGP 8.13.2 e SDK 36. Usar Java no spike não comprova consumo
Kotlin de produção com o compilador 2.1.20 do projeto. A variante 1.0.35 também
foi inspecionada, mas não integrada.

### Método

Um único teste opt-in gera em memória um PDF sintético de três páginas: textual
com acentos em português, imagem sem camada textual e texto com `/Rotate 90`.
O original é escrito no store AES-GCM/Keystore e só os bytes autenticados são
oferecidos ao PDFium. A fonte customizada fornece blocos por callback em memória,
com limites de posição/tamanho e fechamento explícito. Não usa URI externa,
descritor de arquivo em claro ou PDF temporário.

Configuração usa logger sem saída e erro explícito de recurso já fechado.
Documento, página, text page e bitmap são fechados/liberados. O teste verifica:

- Texto com acentos; ausência de texto na página escaneada; raster não vazio nas três páginas.
- Cada caractere não branco possui coordenada que, mapeada pelo PDFium, contém pixels de tinta na prévia; total de 101 caracteres nas duas páginas textuais.
- Oito documentos inválidos rejeitados pelo caminho customizado; conteúdo de exceções não integra o relatório.
- Adulteração AES-GCM rejeitada antes de passar o buffer ao parser.
- Após exclusão do documento não há arquivo novo no diretório privado do teste; procura por assinatura PDF/marcador nos arquivos novos, quando presentes.
- Marcadores sintéticos não aparecem nos logs do PID do teste. O controlador inspeciona logs somente em memória, sem persistir saída bruta.

O APK final tem backups desabilitados e **não possui INTERNET**, removida
explicitamente do manifest de teste. A primeira execução diagnóstica no emulador
herdava INTERNET de dependências: o relatório antigo `api36-custom-buffer` não
comprova essa barreira; os relatórios `api36-offline-custom-buffer` e
`s25-custom-buffer` usam o mesmo APK final sem a permissão. Não houve mudança de
rede/modo avião do telefone; ausência de permissão de socket é a barreira deste
ensaio, não o teste completo de instalação offline da tarefa 8.2.

### Resultados reais

| Medição | Samsung SM-S931B | Emulador arm64 API 36 |
| --- | --- | --- |
| Android/API | 17 / 37 | 16 / 36 |
| Fonte customizada, texto, raster, coordenadas | passou | passou |
| PDFs inválidos / adulteração | 8 rejeitados / rejeitada | 8 rejeitados / rejeitada |
| Marcador no log / arquivos novos após limpeza | não / 0 | não / 0 |
| Kernel de memória | 4096 bytes | 4096 bytes |

No S25: abertura 104 ms, renders 2/2/1 ms, maior amostra PSS 61077 KiB
(aproximadamente 59,6 MiB), execução do método 488 ms. Bootstrap das bibliotecas
ocorre antes do relógio de abertura; a amostra de PSS inclui processo de teste,
dependências e buffers. São amostras por etapa, não pico garantido. Não são
benchmarks representativos do corpus ou documentos grandes.

Relatórios reproduzíveis sem conteúdo em `validation/results/pdfium-2.0.1-*`.
Um teste instrumentado cobre as três páginas por execução; não contar páginas
como três testes independentes. Quatro testes Python do controlador verificam
que timeout, falha de instrumentação ou marcador em logs impedem aprovação.

ARM64 e x86_64 dos dois `.so` têm segmentos LOAD alinhados em 0x4000. As variantes
32 bits têm 0x1000; não são evidência de funcionamento com kernel de 16 KiB.
`zipalign -c -P 16 4` aprovou o APK final. Nenhum teste executou em kernel de
16 KiB ou Android API 24.

### Bloqueios de promoção e patch proposto

A inspeção do JNI da tag 2.0.1 identificou:

1. `initLibraryIfNeed`: `sLibraryLock.unlock()` enquanto o mutex ainda pertence a
   um `std::lock_guard`, que também fará unlock ao sair do escopo.
2. `nativeOpenMemDocument`: `new jbyte[size]` é liberado com `free()` no destrutor.
3. No mesmo caminho, a cópia só é ligada ao owner depois de abrir o PDF; uma
   abertura malsucedida deixa a cópia sem liberação.

A fonte customizada evita o caminho direto `nativeOpenMemDocument`; não resolve
por si só o mutex da inicialização. As execuções funcionais usaram os AARs
originais, sem crash observado; não houve ensaio de sanitizers/concorrência para
reproduzir essas falhas. O vínculo fonte/binário da publicação não é uma prova
reproduzível de build do upstream.

Foi preparada `validation/pdf/pdfium-2.0.1-jni-proposal.patch` contra a tag fixada,
removendo o unlock extra e corrigindo ownership/alocação da cópia. O dry-run
aplicou sem conflito; o patch **não foi aplicado, compilado nem executado**.
Decisão solicitada ao usuário: manter um build JNI próprio com esse patch ou
avaliar outro binding PDFium. Isso acrescenta manutenção de código de terceiros;
a autorização anterior para ensaiar PDFium não é aprovação de binário corrigido.

Licença do binding: Apache-2.0, cópia em `validation/pdf/BINDING_LICENSE`.
O motor `libpdfium.so` vem pré-compilado no repositório do binding. Identificar sua
revisão de origem e reunir os avisos/licenças do PDFium e componentes incorporados
permanece pendente antes da distribuição; a licença do binding não substitui
esses avisos. Não houve integração do modelo/OCR/parser/UI a partir deste spike.

### Reprodução

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/rafaelgontijo/Library/Android/sdk \
./gradlew :pdf-spike:assembleDebugAndroidTest --no-daemon --console=plain

python3 tools/pdf/run_spike.py \
  --adb /Users/rafaelgontijo/Library/Android/sdk/platform-tools/adb \
  --serial RQGL902VZBE \
  --report validation/results/pdfium-novo-ensaio.json

PYTHONPYCACHEPREFIX=/private/tmp/exames-pdf-pycache \
python3 -m unittest discover -s tools/pdf -p 'test_*.py' -v
```

O controlador não sobrescreve relatórios; tem prazos de instalação/execução,
registra hash do APK antes de instalar e encerra somente o pacote do spike.
Sem `pdfSpike=true`, o teste é ignorado, não aprovado. O comando do controlador
passa o opt-in; não fornece documentos externos. A inspeção é técnica e sintética,
sem validação clínica. Tarefa 3.1 permanece aberta e progresso **18/51**.

## Continuação autorizada: JNI recompilado

O usuário autorizou manter o build JNI próprio. O patch, cujo nome conserva
`proposal` por histórico dos links, foi aplicado e recompilado. Foi incluída uma
checagem de entrada nula antes de tocar no JNI array, além das correções de mutex,
allocator e ownership. Fontes/headers estão fixados por hash em
`validation/pdf/native-sources.lock.json` (29 arquivos da mesma tag 2.0.1).
`tools/pdf/rebuild_jni.py` verifica cada entrada, aplica o patch a uma cópia nova e
recompila as quatro ABIs com NDK 27.1.12297006, CMake 3.22.1 e Android API 24.
Os `.so`/AAR gerados ficam apenas em `pdf-spike/build/`, fora do Git.

### JNI corrigido, motor original (perfil 1)

O S25 executou o binário JNI corrigido com o motor original já ensaiado. Além do
PDF híbrido, coordenadas e autenticação, verificou 16 ciclos de abertura/fechamento
pelo caminho direto de bytes; rejeição de null/vazio; e oito aberturas inválidas de
4 MiB. A diferença em `Debug.getNativeHeapAllocatedSize()` após as falhas foi
**0 bytes**. Isso é uma verificação de retenção dessa alocação, não prova de
inexistência de todo vazamento. Limite do teste: menos de 16 MiB de crescimento,
contra 32 MiB que o antigo caminho sem owner poderia reter nesse cenário.

Resultado real: `validation/results/pdfium-2.0.1-s25-patched-jni.json`, aprovado;
API 37, maior amostra PSS 56754 KiB, abertura 51 ms. Esse perfil manteve o motor
original; não resolve sozinho a proveniência/avisos desse binário. Não foram
executados sanitizers nem provada correção universal de concorrência.

### Motor rastreável (perfil 2)

Para resolver a pendência de proveniência/avisos do motor original, foi ensaiado
PDFium **154.0.8035.0**, release fixado
[bblanchon/pdfium-binaries chromium/8035](https://github.com/bblanchon/pdfium-binaries/releases/tag/chromium%2F8035).
URLs, tamanhos, hashes reais dos quatro `.tgz`, bibliotecas e metadados ficam em
`validation/pdf/engine.lock.json`. Os hashes efetivos foram confrontados com os
`digest` da API do release; não foi feita verificação criptográfica de attestation.
A identificação de origem do motor é a versão/branch publicada pelo distribuidor,
sem alegar compilação própria ou reprodução integral do build PDFium.

Os pacotes incluem VERSION, args.gn, licença do distribuidor (MIT) e avisos de
PDFium/componentes incorporados (incluindo BSD/Apache, bibliotecas de imagem e
fontes). São preservados em `validation/pdf/engine-8035/`; o AAR/APK incorpora os
avisos/metadados de cada ABI, 80 arquivos ao todo, além da licença Apache do
binding. Não se deduz a licença do motor a partir da licença do binding.

Os args.gn fixados declaram `pdf_enable_v8=false`, `pdf_enable_xfa=false` e API
mínima 23. A reconstrução exige esses flags e recusa mínimo acima de 24. O
JNI segue compilado para 24. Não há JavaScript, XFA, rede ou entrada externa de
exame neste ensaio. ARM64/x86_64 do motor e todas as variantes do JNI recompilado
possuem LOAD 0x4000; motores 32 bits permanecem em 0x1000. Nenhum kernel de 16 KiB
foi executado.

O perfil 2 passou no emulador arm64/API 36, com o mesmo PDF híbrido, os testes de
ownership e mais 32 ciclos de documentos em duas threads pela API pública
sincronizada. Relatório `validation/results/pdfium-8035-api36-patched-jni.json`:
abertura 2 ms, maior amostra PSS 69238 KiB, delta de alocação nativa após falhas
-15360 bytes. A variação negativa pode refletir liberações do processo; não é uma
medida de ganho ou benchmark. PSS é amostrado na etapa das páginas; o ensaio de
buffers inválidos mede separadamente o delta de bytes nativos alocados. Esse
teste de threads não substitui sanitizer nem
prova todos os interleavings.

O controlador agora confere no próprio APK os hashes de cada motor/JNI para as
quatro ABIs, vínculo com patch/locks atuais e todos os avisos do motor antes de
instalar. Se houver divergência, não instala. Seis testes Python passaram,
incluindo recusa de binário trocado, patch desatualizado e inventário ABI incompleto.
O APK final passou em `zipalign -c -P 16 4`, sem INTERNET e com backup desligado.

AAR corrigido perfil 2: SHA-256
`33a6e34cf77773b3284258170ac8275034ff238255e9a185f657964d9f26d91a`.
Uma segunda reconstrução offline no mesmo computador, caminhos e toolchain
produziu o mesmo hash. Isso não comprova identidade entre sistemas/caminhos ou
reprodução dos binários de motor fornecidos pelo distribuidor. Registro em
`validation/pdf/jni-build-8035.lock.json`.

A tentativa de executar o perfil 2 no S25 parou no controlador durante a perda
de conexão ADB: `validation/results/pdfium-8035-s25-patched-jni.json` não contém
métricas e não é evidência de reprovação do parser. O JNI perfil 1 passou no S25;
o conjunto final perfil 2 aguarda reconexão. Por isso a tarefa 3.1 permanece
aberta e o candidato não foi promovido ao app. A compatibilidade Kotlin de
produção e o restante do pipeline continuam nas próximas tarefas. Progresso
**18/51**, sem declarar validação clínica.

### Reprodução do perfil corrigido rastreável

Requer Python 3.9+, JDK 21, SDK 36, NDK 27.1.12297006 e CMake 3.22.1. O core AAR
original deve estar disponível após resolver as dependências do spike. O script
baixa somente fontes/pacotes fixados e verificados quando ausentes; `--offline`
exige que já estejam no cache ignorado em `pdf-spike/build/`.

```bash
python3 tools/pdf/rebuild_jni.py \
  --sdk "$ANDROID_HOME" \
  --core-aar /caminho/pdfiumandroid-core-2.0.1.aar \
  --engine-lock validation/pdf/engine.lock.json

./gradlew :pdf-spike:assembleDebugAndroidTest \
  -PpdfiumPatchedJni=true \
  -PpdfiumCoreAar=build/native-rebuild/pdfiumandroid-core-2.0.1-exames2.aar

python3 tools/pdf/run_spike.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --serial RQGL902VZBE \
  --native-build-report pdf-spike/build/native-rebuild/build-report-2.json \
  --report validation/results/pdfium-8035-s25-proxima-tentativa.json
```

O default sem `pdfiumPatchedJni` continua sendo o upstream para comparação,
apenas no módulo de spike. Nenhum perfil é dependência de produção. Não declarar
aprovação do JNI corrigido usando relatório do perfil upstream. O perfil original
corrigido pode ser reconstruído omitindo `--engine-lock`; seu AAR recebe sufixo
`exames1`, enquanto o motor rastreável recebe `exames2`.

## Fechamento do spike após reconexão do S25

A combinação final **JNI corrigido + PDFium 154.0.8035.0 (perfil 2)** executou e
passou no Samsung SM-S931B / Android 17 API 37, kernel 4096 bytes. Resultado em
`validation/results/pdfium-8035-s25-patched-jni-retry.json`; a tentativa anterior
sem conexão permanece preservada separadamente, sem métricas.

O controlador validou os hashes atuais de JNI/motor e dos avisos dentro do APK
antes de instalar. O teste aprovou as três páginas híbridas/rotacionadas, 101
caracteres com destaque sobre o raster, oito entradas inválidas via callbacks,
adulteração criptográfica, 16 ciclos válidos pelo caminho direto, 32 ciclos em
duas threads e oito entradas inválidas de 4 MiB pelo caminho corrigido. Entradas
nula/vazia foram rejeitadas. Nenhum marcador foi encontrado no log do PID; nenhum
arquivo novo permaneceu após limpeza.

Medições: abertura 1 ms, maior amostra de PSS na etapa de páginas 55364 KiB,
duração do método 742 ms e delta de bytes nativos alocados após falhas -4320.
A abertura exclui bootstrap das bibliotecas e leitura/autenticação; não é latência
fim a fim. Delta negativo é variação de alocação, não ganho de memória ou prova de
inexistência universal de vazamentos. Não foram executados sanitizers.

**Decisão técnica:** selecionar perfil 2 para a próxima integração, preservando
buffer autenticado, logger sem saída, recursos fechados e parâmetros fixados.
Tarefa 3.1 concluída pelo spike e pela decisão documentada; OpenSpec **19/51**.
A integração de produção (3.3), OCR/limites/pipeline e compatibilidade Kotlin
continuam pendentes. API 24 foi verificada por requisitos/compilação, e 16 KiB por
ELF/ZIP; execução em API 24 ou kernel 16 KiB ainda não foi demonstrada. Os
binários continuam fora do Git. Não há conclusão clínica a partir desse teste.
