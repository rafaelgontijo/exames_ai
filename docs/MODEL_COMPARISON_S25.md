# Comparação de modelos no S25 — 26/10/2026

## Decisão técnica

Selecionado **Gemma 4 E2B GPU para a próxima etapa de integração**, com
LiteRT-LM 0.18.1, sem `ResponseFormat` nativo e com validação externa obrigatória
de JSON completo e evidência. A decisão está fixada em
[`validation/models/selection.json`](../validation/models/selection.json).
Pesos, runtime e pipeline ainda não estão integrados ao app de produção.

O Gemma passou nos dois exemplos tanto na API síncrona quanto na API assíncrona,
usando o leitor JSON estrito. São **duas entradas distintas, repetidas em dois
modos**, não quatro casos independentes nem medição de acurácia geral.

| Ensaio | Critério final | Carga por caso | Geração por caso | Maior PSS amostrada |
| --- | --- | --- | --- | --- |
| Qwen CPU + gramática nativa | Reprovado na primeira entrada | 0,934 s | 3,276 s | 2,02 GiB |
| Qwen GPU + gramática nativa | Reprovado na primeira entrada | 3,706 s | 1,467 s | 2,04 GiB |
| Qwen CPU, não-thinking solicitado + gramática | Reprovado na primeira entrada | 0,992 s | 3,606 s | 2,02 GiB |
| Qwen GPU, não-thinking solicitado, sem gramática | Reprovado na primeira entrada | 3,787 s | 0,802 s | 1,94 GiB |
| Gemma GPU síncrono + gramática nativa | JSON malformado; reprovado | 5,220 s | 1,343 s | 2,87 GiB |
| Gemma GPU síncrono, sem gramática, leitor estrito | 2/2 respostas com 5/5 campos/evidência corretos | 5,796 / 5,774 s | 1,205 / 1,061 s | 2,87 GiB |
| Gemma GPU assíncrono, sem gramática, leitor estrito | 2/2 respostas com 5/5 campos/evidência corretos | 6,860 / 10,187 s | 1,160 / 1,132 s | 2,88 GiB |

Os ensaios reprovados param na primeira falha: a segunda entrada não foi
executada nesses ensaios. A tabela não deve ser convertida em taxas comparáveis
de acurácia. O LFM CPU havia passado nos dois exemplos com o leitor inicial,
mas usou cerca de 7,08 GiB de PSS e levou 33,279 / 19,660 s para gerar; suas
variantes GPU reprovaram. Ver [spike LFM](LFM_S25_SPIKE.md).

## O que a comparação demonstra

Trocar os pesos do LFM pelo Qwen não resolveu os controles testados. O Gemma
também reprovou inicialmente com a gramática nativa. A inspeção automática do
canal final identificou um objeto delimitado por chaves, mas não parseável,
sem bloco Markdown ou delimitador de pensamento detectado. A API síncrona
também reproduziu essa falha: ela não se restringe à montagem por streaming.

Retirar o caminho nativo de `ResponseFormat` no Gemma, conservando o mesmo
arquivo, backend, prompt, schema externo e limites, permitiu respostas completas
corretas. A repetição em streaming também passou. Isso aponta para a combinação
**decodificação estruturada nativa/configuração/runtime** neste ambiente,
não demonstra defeito exclusivo dos pesos LFM e não identifica a causa interna
do SDK. O Qwen continuou reprovando também no controle sem gramática; não se
generaliza o resultado Gemma para todas as famílias ou configurações.

Uma repetição adicional, planejada para parear a gramática nativa com o mesmo
APK do leitor estrito, não chegou a executar: a instalação retornou `device not
found`. Não foi criado relatório para essa tentativa. As comparações executadas
com ResponseFormat e os quatro resultados estritos positivos anteriores
permanecem preservados; não se afirma confirmação adicional no APK final.

Nenhuma saída foi reparada ou aceita por aproximação. O leitor atual rejeita
JSON truncado, chaves duplicadas, tipos não textuais, comentários, aspas simples,
nomes sem aspas, blocos Markdown, prefixos/sufixos e múltiplos objetos. Só o
objeto completo do canal final pode passar. Cada campo e ID de evidência ainda
precisa corresponder literalmente à fonte. Diagnósticos de substring ou trim
não entram no critério de aprovação. O modelo não recebe os valores esperados
no prompt nem numa gramática. O motor determinístico e seus bloqueios não mudaram.

## Condições e rastreabilidade

- Samsung `SM-S931B`, Android 17/API 37, arm64-v8a, MemTotal 11379964 kB,
  kernel com páginas de 4096 bytes. Sem serial nos relatórios.
- Activity sintética em primeiro plano, keyguard desbloqueado verificado,
  sem dispensar o bloqueio do aparelho. Arquivos de pesos privados em `no_backup`.
- LiteRT-LM 0.18.1; AAR SHA-256
  `05c6bdc8acda834efcca12c0ad5d06d2cd8c83e60770f056d8d222f699f3012b`.
- Mesmas duas linhas sintéticas de glicose em português, com valores diferentes;
  contexto 2048, geração até 1024, greedy/topK 1/topP 1/temperatura 0/seed 0.
- Engine nova por requisição, cache nativo desabilitado, somente texto e sem tools.
  Gemma usa canais de seus metadados; não herda controles de Qwen/LFM.
- PSS via `Debug.getPss()` a cada 100 ms. Não é pico garantido nem memória total
  do driver GPU. Carregamentos podem aproveitar caches do SO; não são uma
  campanha controlada de cold start. Geração inclui processamento da entrada.
- Limite externo de 120 s por fase; heartbeats não prorrogam esse prazo.
  O controlador encerra somente o pacote de teste ao terminar. Isso não comprova
  o cancelamento JNI ou a implementação de isolamento da aplicação futura.
- Pesos verificados integralmente no host e no aparelho; não estão no Git.
  Relatórios preservam hashes, versões, configuração e métricas, sem pensamento,
  texto gerado ou dados pessoais. Contagem de callbacks não é contagem de tokens.

Pins/licenças/proveniência:
[Qwen](../validation/models/qwen3-0.6b/model.lock.json) e
[Gemma](../validation/models/gemma4-e2b/model.lock.json).
O Qwen usa a revisão `e6687177e00897731e7716267eebd4eba0dde7c2`, arquivo
`Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`, 344671744 bytes, SHA-256
`03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856`.
O Gemma usa a revisão `1caa527c1e88b55f046fcd8d2b4265606680663d`, arquivo
`gemma-4-E2B-it-gpu.litertlm`, 2008432640 bytes, SHA-256
`a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5`.
Cards declaram Apache-2.0; cópias da licença e proveniência estão junto dos pins.
O link de licença do card-base Gemma aponta à [licença publicada pelo Google](https://ai.google.dev/gemma/apache_2).

## Reproduzir o caminho aprovado do spike

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Users/rafaelgontijo/Library/Android/sdk
./gradlew :inference-experimental:assembleDebugAndroidTest --no-daemon --console=plain
python3 tools/litert/run_spike.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial SERIAL_ADB \
  --model /Users/rafaelgontijo/Workspace/.model-cache/gemma4-e2b/gemma-4-E2B-it-gpu.litertlm \
  --model-id gemma4 --backend GPU --foreground --english-prompt \
  --context-tokens 2048 --output-tokens 1024 \
  --report validation/results/gemma4-nova-execucao.json
```

Usar `--reuse-device-model` apenas quando o mesmo arquivo já estiver no pacote;
o hash no aparelho continua obrigatório. `--synchronous` seleciona o controle
sem streaming; acrescentar `--structured-json` reproduz o caminho nativo reprovado.
Não passar `--qwen-no-thinking` ao Gemma/LFM: o harness recusa essa combinação.

Relatórios aprovados com leitor estrito:
[síncrono](../validation/results/gemma4-s25-spike-2026-10-26-gpu-sync-strict.json),
[assíncrono](../validation/results/gemma4-s25-spike-2026-10-26-gpu-async-strict.json).
Os demais ensaios, inclusive falhas, permanecem em `validation/results/`.

## Verificação e limites

APK instrumentado compilado com JDK 21/SDK 36. Dez testes Python do controlador
passaram. Quatro testes instrumentados de protocolo JSON passaram no S25,
incluindo rejeição de wrappers, duplicatas, tipos inválidos e truncamento.
O APK não declara INTERNET, mantém backup desabilitado e passou em
`zipalign -c -P 16 4`. O ELF arm64 do AAR tem segmentos LOAD alinhados a 16 KiB.
São verificações estáticas de 16 KiB; não houve teste em kernel de 16 KiB.

O spike permite a decisão técnica da tarefa 4.7. Ele **não** conclui integração
Kotlin/DI, distribuição offline no APK, parser dos cinco analitos, corpus/A-B,
esquema de ausências/ambiguidades, cancelamento robusto ou UI. O harness Java
isola a incompatibilidade entre metadados Kotlin 2.4 do AAR e o compilador 2.1.20
do projeto; isso ainda precisa ser resolvido na integração. Não houve validação
clínica, testes em 4 GB, execução do modelo em API 24 ou ampliação do escopo clínico.
