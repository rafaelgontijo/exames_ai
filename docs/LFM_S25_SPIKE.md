# Spike LFM no S25 — 26/10/2026

A comparação posterior com Qwen e Gemma, a decisão técnica atual e a distinção
entre erro de modelo e caminho de decodificação estão em
[MODEL_COMPARISON_S25.md](MODEL_COMPARISON_S25.md). Os resultados abaixo do LFM
permanecem preservados; a escolha posterior foi Gemma GPU sem ResponseFormat nativo.

## Resultado atual

O timeout deixou de ocorrer na configuração CPU estruturada: **os dois casos
sintéticos passaram**, com valor diferente e uma Engine nova por solicitação.
Ainda não é uma aprovação do pipeline ou do modelo sobre o corpus.

| Configuração | Carga da Engine | Geração até resposta completa | Maior PSS amostrada | Campos/evidência |
| --- | --- | --- | --- | --- |
| CPU INT4, caso 0 | 12,904 s | 33,279 s | 7,05 GiB | 5/5 corretos |
| CPU INT4, caso 1 | 21,655 s | 19,660 s | 7,08 GiB | 5/5 corretos |
| GPU INT4, caso 0 | 11,652 s | 1,954 s | 2,06 GiB | 5/5 corretos |
| GPU INT4, caso 1 | 14,212 s | 2,396 s | 2,06 GiB | 0/5 corretos; reprovado |
| GPU INT4, FLOAT32 solicitado, caso 0 | 16,644 s | 2,176 s | 2,64 GiB | 0/5 corretos; reprovado |

Relatórios: [CPU aprovada neste spike](../validation/results/lfm-s25-spike-2026-10-26-required-fields.json)
e [GPU com divergência](../validation/results/lfm-s25-spike-2026-10-26-gpu.json).
O [controle GPU float32](../validation/results/lfm-s25-spike-2026-10-26-gpu-float32.json)
também reprovou, já na primeira entrada; a segunda não executou. Não houve timeout
ou indicação de crash nesse controle. A conexão ADB permaneceu ativa ao final.
Os tempos de geração incluem o processamento da entrada e a geração; não são
tempos de decode isolado. Cargas seguintes podem aproveitar caches do sistema
operacional; Engine nova não implica cache frio do aparelho. PSS não é pico
garantido nem contabilização completa da memória do driver GPU.

A configuração CPU que passou utiliza tela de teste visível/desbloqueada,
streaming, contexto 2048, saída máxima 1024, instruções internas em inglês com
mapeamento explícito das colunas e documento em português. O runtime impõe um
JSON Schema com cinco strings obrigatórias de 1 a 128 caracteres. O schema
**não contém analitos, valores, unidades, faixas ou IDs esperados**. A validação
posterior continua exigindo suporte literal de todos os campos, inclusive
analito e ID da evidência. Não se aceitou string vazia, campo inventado ou
resposta parcialmente correta. Este schema é específico do spike com entrada
completa; o schema de produção ainda deve representar ausências/ambiguidades.

Não foi usado `ThinkingConfig` nessa configuração estruturada, mantendo limite
total de geração e separação do canal thought. Isso não comprova desligamento
do raciocínio. A combinação de orçamento separado de pensamento e JSON Schema
tem [problema relatado no runtime](https://github.com/google-ai-edge/LiteRT-LM/issues/3463),
reproduzido pelos mantenedores em outro modelo/versão/plataforma; não é prova da
causa local. A API utilizada está em
[ResponseFormat da versão 0.18.1](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/kotlin/java/com/google/ai/edge/litertlm/ResponseFormat.kt).

Os experimentos intermediários foram preservados. O streaming sem schema
mostrou callbacks e conteúdo final antes do timeout, contrariando a hipótese
de bloqueio completo. JSON Schema sozinho encerrou a resposta, mas os campos
não passaram na evidência. Instruções explícitas/contexto menor acertaram quatro
campos e deixaram o analito vazio. Exigir strings não vazias, sem especificar
seus valores, permitiu os dois acertos CPU. As mudanças foram combinadas ao
longo dos ensaios; não atribuir todo o efeito a um único parâmetro.

Durante um ensaio anterior o aparelho estava em Dozing, com MemAvailable de
774888 kB, SwapFree de 3590636 kB em SwapTotal de 12582908 kB e status térmico 2.
A Activity de teste mantém apenas sua tela acesa, sem dispensar keyguard.
Foreground sozinho não resolveu a extração; reduzir contexto não eliminou o
alto consumo CPU observado. Os dados não permitem atribuir causalidade única
à suspensão, pressão de memória ou temperatura.

GPU permanece experimental: a velocidade menor não compensa a reprovação do
segundo caso. Após uma tentativa impedida por desconexão, o controle com
`ActivationDataType.FLOAT32` executou e reprovou nos cinco campos da primeira
entrada. Solicitar float32 não resolveu a divergência neste ensaio; não foi
comprovada sua causa nem a precisão efetiva de todas as operações internas.
Também não se comprovou
isolamento geral entre requisições a partir desses dois exemplos.

Na conclusão desta etapa LFM, **15/51 tarefas**: 4.7 continuava aberta para fechar a escolha de runtime/backend
e seus limites. CPU tem evidência positiva restrita a duas linhas; memória alta,
divergência GPU e incompatibilidade Kotlin de produção ainda exigem tratamento.
Sem empacotamento do modelo, comparação A/B sobre corpus ou validação clínica.

## Etapa inicial

Ensaio real em Samsung `SM-S931B`, Android 17/API 37, ABI arm64-v8a,
`MemTotal: 11379964 kB` e páginas de memória de 4096 bytes. O número de série
não integra o relatório versionado. Os pesos foram verificados novamente no
computador e no armazenamento privado do pacote de teste antes da execução.

O relatório está em
[`validation/results/lfm-s25-spike-2026-10-26.json`](../validation/results/lfm-s25-spike-2026-10-26.json).

- A primeira solicitação entrou em `load` e depois em `generate`: `Engine.initialize()` retornou.
- O ensaio encerrou reprovado, sem evento de resultado/JSON. O controlador não atingiu seu timeout.
- A duração de 91,793 s é do controlador durante instrumentação/encerramento, não latência de geração medida.
- A segunda solicitação planejada não foi executada. Não há medição disponível de memória, tokens ou isolamento neste resultado.
- A causa não pode ser determinada pelo relatório original; ele não capturava a categoria da falha. Não atribuir crash, OOM ou erro de schema sem evidência.
- A repetição com diagnóstico sanitizado falhou antes da instalação com `device not found`; `adb devices -l` confirmou lista vazia. Nenhum relatório de inferência adicional foi gerado.

Na etapa inicial não houve sucesso de extração, comparação A/B, OCR, GPU, reset seguro,
cancelamento JNI comprovado ou validação clínica. A tarefa 4.7 permanece aberta,
assim como a seleção definitiva do modelo/runtime. O progresso continua 15/51.

## Repetição após reconexão

O S25 foi reconectado e o mesmo teste foi repetido, com diagnóstico sanitizado.
Relatório separado, preservando o anterior:
[`lfm-s25-spike-2026-10-26-diagnostic.json`](../validation/results/lfm-s25-spike-2026-10-26-diagnostic.json).

- A carga concluiu em **15458 ms** e a fase de geração começou.
- A geração não retornou resultado dentro dos **120 s** configurados. O controlador
  registrou `timedOut=true` e encerrou o pacote de teste via `am force-stop`.
- O tempo total do controlador foi **137,628 s**, incluindo carga e encerramento;
  não representa uma geração concluída.
- A conexão ADB permaneceu ativa após o timeout. Nesta repetição, a desconexão
  não explica a ausência de resultado.
- Não houve JSON, exceção sanitizada ou encerramento normal observado antes do
  limite. O campo `processCrashed=false` significa que não foi recebida a indicação
  de crash da instrumentação; não é prova de funcionamento correto do runtime.
- A segunda entrada não foi executada. Memória e tokens continuam sem medição
  reportada; não houve oportunidade de validar isolamento entre duas solicitações.

Este ensaio não distingue geração lenta, raciocínio que não termina ou bloqueio
nativo. O prazo de 120 s da configuração atual não foi atendido; o modelo não é
considerado aprovado. Não alterar o resultado anterior nem marcar 4.7 concluída.

## Condições do primeiro experimento

Runtime 0.18.1 fixado apenas em `androidTest` de `:inference-experimental`.
LFM CPU INT4 na revisão/hash de `validation/models/lfm2.5-1.2b-thinking/model.lock.json`;
quatro threads CPU, contexto total 4096, saída máxima 2048, orçamento de pensamento
solicitado 300 (efetividade não comprovada), topK 1, topP 1, temperatura 0, seed 0.
Engine nova por solicitação e cache desabilitado (`:nocache`).

O teste planeja duas linhas sintéticas em português com o mesmo prefixo e valores
distintos. Valida apenas cinco strings de JSON e suporte literal na fonte.
Não recebe documentos pessoais. Lê somente conteúdo final; o canal `<think>`
é separado e nunca é lido, exibido ou persistido. O relatório contém métricas e
estados, sem texto gerado, prompt ou pensamento. Tokens thought/final ficam
indisponíveis. O diagnóstico adicional registra somente classe de exceção e
código de erro de uma lista fixa, nunca a mensagem da exceção.

O controlador limita cada fase a 120 s e força a parada **do pacote de teste**
ao terminar. Isso protege o experimento; não comprova cancelamento seguro de JNI
na integração de produção. A PSS é amostrada via `Debug.getPss()` a cada 100 ms
quando há resultado; não representa pico garantido.

## Reproduzir

No host atual, JDK 21 instalado via Homebrew, SDK 36 existente:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Users/rafaelgontijo/Library/Android/sdk
./gradlew :inference-experimental:assembleDebugAndroidTest --no-daemon --console=plain
python3 tools/litert/run_spike.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --serial SERIAL_ADB \
  --model /Users/rafaelgontijo/Workspace/.model-cache/lfm2.5-1.2b-thinking/LFM2.5-1.2B-Thinking_int4.litertlm \
  --report validation/results/lfm-s25-spike-nova-tentativa.json
```

O script instala somente `br.com.exames.inferenceexperimental.test`, envia os pesos
para `no_backup/models/lfm.litertlm` desse pacote e usa a instrumentação opt-in
`lfmSpike=true`. Sem esse argumento, o teste é ignorado nas suítes comuns.
Provisionamento manual é exclusivo do spike; não satisfaz a distribuição offline
do modelo com o APK. O app de produção não recebeu runtime nem pesos nesta etapa.
O APK de teste tem targetSdk 36, minSdk 24, backup desabilitado e nenhuma permissão INTERNET.

O APK passou em `zipalign -c -P 16 4`; os três segmentos LOAD do ELF arm64 no AAR
têm alinhamento `0x4000`. O aparelho conectado usa páginas de 4 KiB: não declarar
execução em kernel de 16 KiB a partir dessas verificações estáticas.

JDK 17 não lê as classes Java 21 do AAR. O compilador Kotlin 2.1.20 do projeto
não lê seus metadados 2.4. O harness usa Java para isolar o ensaio, sem desativar
checagens; D8/AGP 8.13.2 ainda emitiu avisos de reescrita de metadados Kotlin.
O build terminou, mas esses avisos e a integração Kotlin exigem decisão de
compatibilidade antes de promover o runtime a produção. Ver `LITERT_COMPATIBILITY.md`.

Para repetir a configuração CPU que passou, acrescentar `--reuse-device-model
--structured-json --foreground --english-prompt --context-tokens 2048
--output-tokens 1024` ao comando acima e usar um novo caminho de relatório.
Se o último teste foi GPU, omitir `--reuse-device-model` para repor o arquivo CPU
privado; a conferência integral de hash no aparelho impede usar a variante errada.
O script recusa sobrescrever relatórios. Heartbeats não estendem o prazo por fase;
três testes Python verificam esse comportamento.

Para repetir o controle GPU, usar o arquivo `_int4_gpu.litertlm`,
`--backend GPU --float32` e os mesmos parâmetros estruturados. Cada variante
possui tamanho/hash próprios, verificados antes da cópia e antes da inferência.
Os pesos continuam fora do Git. O S25 continuava conectado na última consulta.
A CPU LFM permanece como referência positiva restrita aos dois exemplos; ambos
os ensaios GPU LFM permanecem reprovados. A decisão posterior de runtime/backend
usa a comparação com Gemma e está registrada no relatório comparativo acima.
