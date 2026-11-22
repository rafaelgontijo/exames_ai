# Modelo empacotado e adaptador local

`:inference-local` substitui o stub. O app inclui LiteRT-LM 0.18.1 e fornece
`TextInference` por Hilt, com contrato JVM em `:domain`. BASELINE retorna
`NotRequested` antes de preparar pesos. ASSISTED confere o modelo privado e
cria Engine/conversa novas, GPU, contexto/saída 2048/1024 e cache `:nocache`.
Os tipos SDK ficam no adaptador Java. Somente contents alimenta a resposta;
channels/thought não são inspecionados nem persistidos.

A saída é `UnvalidatedFinal`. A ativação na UI da bancada, prompt/schema de
extração, validação de evidência/associação e deadlines/cancelamento JNI seguem
pendentes. O adaptador atual aguarda a chamada nativa terminar e descarta saída
após cancelamento; isso não limita seu tempo. A tarefa 4.8 permanece aberta.

## Build e preparação

Um modelo é selecionado em `validation/models/selection.json`. O Gradle executa
`tools/litert/stage_model.py`, verificando tamanho/hash/revisão e licença, e copia
para `app/build/generated/model-assets/models/`, ignorado pelo Git. O asset
`.litertlm` não é comprimido; manifesto e licença Apache-2.0 acompanham os pesos.

O caminho padrão é
`../.model-cache/gemma4-e2b/gemma-4-E2B-it-gpu.litertlm`. Outra localização exige
`-PexamesModelPath=/caminho/arquivo.litertlm`. O app não baixa arquivos; o staging
falha se o pin estiver ausente/divergente. Preparar também o AAR PDF corrigido
conforme `DOCUMENT_EXTRACTION.md` antes de compilar.

`BundledModelStore` copia do asset para `noBackupFilesDir/models/gemma4.litertlm`,
com tamanho/hash durante escrita, fsync e rename atômico. Trava local e lock de
arquivo serializam preparadores; pesos existentes são novamente conferidos por
hash. Corrupção permite recópia do asset, parciais órfãos são removidos sob lock,
cancelamento/falha não publica parcial. Espaço livre mínimo: tamanho do modelo
mais 64 MiB de reserva, após instalar o APK. Pesos estáticos são compartilhados.

Gemma tem 2008432640 bytes, SHA-256
`a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5`.
O APK universal de validação medido tem 2142540333 bytes; instalação mais cópia
privada exigem vários GB. Conferir tamanho à medida que o aplicativo cresce.

JDK 21, Kotlin 2.3.21, Hilt 2.58, Room 2.8.5, AGP 8.13.2, SDK 36/minSdk 24.
O runtime traz metadados Kotlin 2.4. D8/R8 foi fixado em **9.1.31**, publicado
no Google Maven, usando o override oficial. 9.1.29, mínimo citado pela tabela
Android, não está publicado nesse Maven. O leitor `kotlin-metadata-jvm:2.3.21`
foi acrescentado ao classpath de processadores Hilt/Room, mantendo `readStrict`.
Não houve skip de metadados nem downgrade forçado de stdlib.

`./gradlew verifyDexerVersion` confirmou 9.1.31, build
`60751e845e0a27ed4dd4f3d50e53c9f196fa30bb`. Build debug e testes JVM passaram.
R8 com minify, execução API 24/kernel 16 KiB continuam pendentes. Alinhamento ZIP
16 KiB e hashes/notices PDF do APK foram conferidos.

Fontes: [Android/Kotlin](https://developer.android.com/build/kotlin-support),
[override R8](https://r8.googlesource.com/r8/),
[readStrict](https://kotlinlang.org/api/kotlinx-metadata-jvm/kotlin-metadata-jvm/kotlin.metadata.jvm/-kotlin-class-metadata/-companion/read-strict.html),
[contents/channels](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/kotlin/java/com/google/ai/edge/litertlm/Message.kt).

## Evidência no S25 — 27/10/2026

SM-S931B, Android 17 API 37, kernel 4096 bytes, RAM reportada 11379964 KiB.
Somente fixtures sintéticas; nenhuma validação clínica:

- 30 instrumentados de persistência/PDF/OCR/limites/parser aprovados.
- Cinco testes de modelo aprovados: cópia, hash, ausência, corrupção, truncamento,
  excesso, falha de leitura, cancelamento e concorrência. Falta de espaço foi
  simulada por fornecedor de espaço livre, sem encher o telefone.
- Adaptador de produção aprovou os dois controles com Engines novas. Carga
  9152/9926 ms; geração 1363/1119 ms. A primeira tentativa falhou antes de carregar;
  a repetição após desbloquear passou. Memória não foi amostrada neste controle.
- Instalação limpa em modo avião/Wi-Fi desligado aprovou preparação somente do
  asset e inferência pela DI do app. Carga 8386 ms; geração 1302 ms, excluindo
  staging/cópia/hash. O pacote separado `br.com.exames.offline.validation` e seu
  test APK foram removidos; dados do app existente foram preservados.

Relatórios em `validation/results/private-extraction-s25-2026-10-27.json`,
`gemma4-s25-production-adapter-2026-10-27-unlocked.json` e
`gemma4-s25-bundled-offline-2026-10-27.json`. O último inclui hashes dos APKs e
limpeza. O controle do adaptador usa provisionamento exclusivo do harness; a
instalação limpa usa somente o APK. Não são corpus completo nem fluxo de UI.

Repetição limpa em IDs descartáveis, aparelho desbloqueado e modo avião com
Wi-Fi desligado, preservando USB:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PvalidationApplicationId=br.com.exames.offline.validation
python3 tools/litert/run_bundled.py --adb "$ANDROID_HOME/platform-tools/adb" --serial SERIAL --report /caminho/novo-relatorio.json
```

O controlador recusa substituir pacotes preexistentes e verifica identidade dos
APKs antes de instalar. O estado Wi-Fi `3` significa desativado pelo modo avião
([AOSP](https://android.googlesource.com/platform/packages/modules/Wifi/+/ab7b9b91f4/service/java/com/android/server/wifi/WifiSettingsStore.java)).
O deadline do host é 180 s, com encerramento/remoção somente de seus pacotes;
isso não substitui deadlines do app (4.12). O workflow CI foi atualizado para
JDK 21 e staging fixado; sua execução remota não foi realizada nesta sessão.

## Evidência antes da avaliação (4.11)

`ProcessBiochemistryPage`, caso de uso JVM fornecido por Hilt, recebe somente
IDs da execução/documento, página, fragmentos da fonte e modo. Primeiro executa
o parser, depois, no modo assistido, compila o prompt e encaminha exclusivamente
o canal final a `ProposalProtocol` e `ProposalEvidenceValidator`. Só após essas
barreiras chama `DeterministicRangeEvaluator`, o mesmo motor do modo sem IA.
BASELINE não chama o adaptador de inferência.

A validação verifica texto citado, sequência de spans, identidade do campo,
analito, página, geometria e contexto. O ID de span isolado não basta: texto,
região, método, versão de extrator e confiança devem corresponder à evidência
do parser. IDs desconhecidos/repetidos, faixa inventada, campo da linha errada,
analito não suportado, omissão/duplicação e divergência rejeitam a proposta.
Nenhuma ambiguidade anterior é removida, mesmo quando o modelo concorda.

Conflito semântico conserva os campos brutos/candidatos e acrescenta barreira
de revisão antes da avaliação. Falha do adaptador, ausência de final ou schema
inválido preserva o caminho sem IA, com FALLBACK explícito. O parser ter sucesso
nesses casos não é sucesso de inferência. O resultado contém disposição,
motivos técnicos, issues e durações disponíveis; não retém prompt, resposta
gerada, pensamento ou mensagem de exceção do SDK. Persistência da configuração
e da tentativa foi implementada posteriormente em 4.13; ver
[INFERENCE_TRACE.md](INFERENCE_TRACE.md).

Nesta etapa o modelo pode corroborar somente associações sustentadas pelo parser
conservador; não acrescenta linhas/faixas nem escolhe entre alternativas para
aumentar cobertura. Cancelamento é propagado e uma resposta tardia não autoriza
avaliação. Isso não comprova interrupção do JNI: deadlines/isolamento continuam
na tarefa 4.12, antes de ativar a bancada assistida.

Testes JVM exercitam o caso de uso completo com respostas controladas, incluindo
instruções maliciosas no documento, truncamento, pensamento misturado ao JSON,
diagnóstico inesperado e backend que retorna após cancelamento. No emulador,
texto PDF real passa pelo novo caso de uso; uma resposta simulada sem suporte
leva as cinco observações a NEEDS_REVIEW. Esses testes verificam as barreiras,
não a capacidade do Gemma de produzir o protocolo. Por orientação do usuário,
os ensaios de modelo real no S25 ficam para a etapa final; a tarefa 4.10 e a
aceitação/ativação assistida permanecem pendentes.

## Processo dedicado e deadlines — implementação posterior

O adaptador agora hospeda preparação e JNI em serviço privado de um processo
descartável por solicitação, com prazos de carga/geração de 120 s cada e
encerramento/cancelamento por morte do filho. O processo principal agenda
manutenção somente em sua própria inicialização. Controles de IPC/lifecycle
passaram no emulador sem modelo; a aceitação com Gemma do novo caminho ainda
não foi executada. Os resultados S25 anteriores desta página são históricos
do adaptador anterior. Limites, quotas, métricas e critérios pendentes em
[INFERENCE_PROCESS.md](INFERENCE_PROCESS.md). A tarefa 4.12 continua aberta,
assim como ativação da bancada. O diário protegido de tentativas foi
implementado posteriormente em [INFERENCE_TRACE.md](INFERENCE_TRACE.md).
