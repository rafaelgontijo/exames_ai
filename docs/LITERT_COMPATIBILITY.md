# Verificação de compatibilidade LiteRT-LM

**Atualização de 27/10/2026:** o adaptador de produção, DI e instalação limpa
com pesos no APK passaram no S25 offline. D8/R8 9.1.31 e leitor de metadados
2.3.21 resolveram os bloqueios de build descritos no histórico abaixo. Ativação
na UI, schema/evidência e deadlines continuam pendentes. Ver
[BUNDLED_INFERENCE.md](BUNDLED_INFERENCE.md) para comandos e limites atuais.

Inspeção em 21/10/2026 dos AARs publicados em Google Maven, sem executar o modelo. Após a inspeção, o usuário aprovou elevar o minSdk para 24.

| Versão | minSdk declarado | SHA-256 do AAR inspecionado |
| --- | --- | --- |
| 0.15.0 | 24 | `b398c4745934a6035d192ffce5fdaf4f72a0009830a97b73c017c21f2a92b5bd` |
| 0.16.1 | 24 | `e407719c1a29f2685fcb6aa3feea0b9f7155fe316c66dae053c1b5b2f54cda73` |
| 0.17.1 | 24 | `a8aeaa6b128e9b0f1c7fe5e620932a7030cfff1ac9c0dc133d8c79711de5cf4d` |
| 0.18.1 | 24 | `05c6bdc8acda834efcca12c0ad5d06d2cd8c83e60770f056d8d222f699f3012b` |

Fonte: `https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/<versão>/litertlm-android-<versão>.aar`. Cada `AndroidManifest.xml` declara `android:minSdkVersion="24"`. O AAR 0.18.1 tem 20905360 bytes e bibliotecas nativas para `arm64-v8a` e `x86_64`. Uma inspeção adicional com readelf confirmou alinhamento 0x4000 (16 KiB) nos segmentos LOAD do liblitertlm_jni.so arm64-v8a do AAR 0.18.1. Isso não comprova execução do modelo, alinhamento final do APK ou desempenho no S25.

O app e todos os módulos Android agora declaram minSdk 24 por autorização explícita do usuário. O desenho aprovado exige verificar o runtime sem elevar API mínima silenciosamente. As versões inspecionadas compatíveis com o piso publicado do modelo LFM não resolvem esse conflito por downgrade. Não foi aplicado `tools:overrideLibrary` para forçar uso em API não declarada pelo fornecedor.

Decisão aprovada: elevar a compatibilidade mínima para API 24 (Android 7.0). Android 6.0 deixa de ser suportado; o S25 permanece dentro do público-alvo. Não foi necessário sobrescrever a declaração mínima do fornecedor.

Foi provisionado um emulador API 24 x86_64 para os testes instrumentados de persistência e Keystore. A execução do modelo no S25 permanece pendente.

Os resultados executados de corpus, domínio e persistência estão em `docs/VALIDACAO.md`. A inspeção do AAR não implica que o modelo já foi integrado ou executado.

## Compatibilidade de compilação — 26/10/2026

O AAR 0.18.1 baixado pelo Gradle nesta retomada tem os mesmos 20905360 bytes e
SHA-256 `05c6bdc8acda834efcca12c0ad5d06d2cd8c83e60770f056d8d222f699f3012b`.
Entretanto, `javap -v` mostra metadados Kotlin `mv=[2,4,0]` e classes Java 21
(major version 65). Seu POM declara kotlin-reflect 2.4.0, coroutines-android
1.11.0 e Gson 2.14.0. Esses requisitos não haviam sido cobertos pela inspeção
anterior de minSdk/ABI/alinhamento.

No primeiro ensaio, o teste Kotlin falhou com o então compilador 2.1.20 por incompatibilidade de
metadados. O spike isolado usa a API Java publicada; requer executar o Gradle
com JDK 21, já permitido pela governança. Isso não demonstra integração Kotlin
de produção e não ignora verificações de versão. Na integração PDF posterior,
o projeto passou a Kotlin 2.3.21/Hilt 2.58/Room 2.8.5; esse alinhamento não é
validação de LiteRT-LM em produção, que ainda requer integração/testes próprios.
O runtime é dependência somente de `androidTest` em
`:inference-experimental` até a decisão de integração.

Fontes da API fixadas em
[Engine](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/kotlin/java/com/google/ai/edge/litertlm/Engine.kt),
[Config](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/kotlin/java/com/google/ai/edge/litertlm/Config.kt)
e [Message](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/kotlin/java/com/google/ai/edge/litertlm/Message.kt).

O APK Java do spike compilou com JDK 21 e passou em `zipalign -c -P 16 4`.
D8 emitiu avisos de reescrita dos metadados Kotlin 2.4; isso ainda precisa ser
resolvido antes da integração de produção. O primeiro ensaio real chegou à
geração no S25, mas não registrou resultado e reprovou. Após reconexão, a repetição
diagnóstica carregou o modelo em 15458 ms e atingiu o timeout de geração de 120 s,
sem JSON. O S25 continuou conectado. Não há causa nativa identificada nem aprovação
do modelo/runtime nessa etapa inicial. Ver [relatório do spike](LFM_S25_SPIKE.md).

O diagnóstico posterior obteve dois JSONs corretos na CPU com schema estruturado,
instruções explícitas e campos obrigatórios não vazios, mantendo validação literal
independente. Geração: 33,279 s e 19,660 s; PSS máxima amostrada: aproximadamente
7,08 GiB. A variante GPU própria, também verificada por hash, gerou em 1,954 s e
2,396 s com PSS amostrada de 2,06 GiB, mas reprovou na evidência do segundo caso.
GPU não foi promovida. Após reconexão, o controle GPU com `ActivationDataType.FLOAT32`
executou e reprovou já na primeira entrada (0/5 campos), sem timeout: carga
16,644 s, geração 2,176 s e PSS máxima amostrada 2,64 GiB. O segundo caso não
executou. Float32 não resolveu a divergência nessa tentativa; sua causa continua
indeterminada. A integração Kotlin de produção continua pendente; não confundir
o sucesso do harness Java com conclusão da tarefa 4.8 ou do MVP.

## Escolha após comparação com Qwen/Gemma

O Qwen3 0.6B fixado também reprovou nos controles CPU/GPU executados. O Gemma 4
E2B GPU gerou JSON malformado com ResponseFormat nativo, tanto em streaming quanto
na API síncrona. Sem esse caminho nativo, passou nos dois exemplos em ambas as
APIs com leitor JSON estrito e evidência literal. Escolha técnica para integração:
Gemma GPU, LiteRT-LM 0.18.1, validação externa obrigatória, contexto/saída 2048/1024,
Engine nova e canais do próprio modelo. A decisão está em
`validation/models/selection.json` e a evidência em `MODEL_COMPARISON_S25.md`.

Geração no controle assíncrono: 1160/1132 ms, carga de Engine 6860/10187 ms e maior
PSS amostrada de 3014967 KiB (2,88 GiB). Esses dados não são desempenho garantido
nem validação geral. A compatibilidade Kotlin de produção, o kernel de 16 KiB e
a execução do modelo em API 24 continuam fora da evidência executada. O alinhamento
ZIP/ELF foi verificado estaticamente; a integração de produção não foi concluída.

## Protocolo de produção aceito no S25 — 03/11/2026

O controle `native-production-s25-2026-11-03-protocol3.json` passou em instalação
limpa, modo avião e Wi-Fi desligado, no SM-S931B/Android 17/API 37. Modelo/runtime,
backend, canal final e fresh Engine/processo permanecem fixados em `selection.json`.
O protocolo atual é `biochemistry-evidence/3`, schema `biochemistry-fields/1`.
Duas linhas com prefixo comum passaram schema e evidência literal em PIDs distintos
22186/22228. Carga: 9769/11741 ms; geração final: 1999/1814 ms; PSS máximo
amostrado: 3001427/3000597 KiB. RAM total reportada: 11653074944 bytes.

O controle malicioso foi aceito somente com os campos originais sustentados pela
fonte, sem confirmação fabricada. Excesso de bytes foi rejeitado antes de gerar;
contexto nativo excessivo retornou RUNTIME_FAILURE sanitizado. Cancelamento após
alocação nativa encerrou o filho dentro do limite do teste e não publicou retorno
tardio. Os controles simulados de deadlines complementam esse resultado real.

As tentativas anteriores foram preservadas: falha de Espresso antigo no Android
17; prompt v1 devolveu campos escalares sem spans; v2 produziu JSON incompleto.
O prompt v3 usa exemplo concreto e exige fechamento de array/raiz. O validador não
foi relaxado nem passou a completar JSON. Todas as respostas anteriores foram
rejeitadas. Os diagnósticos exportam somente forma/pontuação/metadados, sem texto
fonte, final ou raciocínio. As contagens agregadas do SDK também vieram nulas;
nenhuma contagem de tokens ou motivo de truncamento é inferido desses nulos.

A configuração de produção pode agora ativar o modo assistido no aparelho de
referência. Isso comprova duas linhas sintéticas e controles nativos, não o corpus
completo, desempenho clínico ou o aceite A/B do fluxo, que tem ensaio separado.

## Estado atual após o A/B completo — 03/11/2026

O aceite das duas linhas v3 acima não se estendeu aos oito documentos completos:
o relatório `final-workbench-s25-2026-11-03.json` contém oito pares, todos com
fallback, e `passed=false`. Não houve aprovação de IA nem execução das fases
posteriores de correção/reinício/exclusão desse controlador. As referências
foram omitidas no PDF digital; houve também schema inválido e associação/divergência
rejeitadas. Diagnósticos v3/v4 foram preservados em `validation/results/`.

A versão atual é `biochemistry-evidence/5`, descrita em `SOURCE_PRESENTATION.md`.
O schema/validador permanecem estritos, sem reparação de JSON ou redução do corpus.
O sinal de aceite foi desativado novamente. O S25 desconectou antes da instalação
v5; o teste físico e novo A/B aguardam reconexão. Portanto a configuração atual
não pode ser declarada aceita com base no relatório v3.

## Aceite final atual v5 — 03/11/2026

Após a reconexão, o protocolo v5 passou nos controles nativos reais e a bancada
passou em instalação limpa offline com oito pares DEVELOPMENT, preparação/modes
pela UI, correção, métricas originais preservadas, force-stop, reabertura privada
e exclusão com pesos estáticos preservados. Um PDF digital simple-v1 teve sua
página aceita pelo modelo; os outros sete casos ficaram em fallback explícito.
O sinal de aceite está ativo somente no S25 de referência. Isso conclui o fluxo
técnico, sem aprovar qualidade geral da extração ou validação clínica. Os detalhes,
regressões e limites estão em [S25_FINAL_VALIDATION.md](S25_FINAL_VALIDATION.md).
