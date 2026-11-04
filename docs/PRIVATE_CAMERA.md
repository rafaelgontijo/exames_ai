# Câmera privada

Captura de uma página por vez, com prévia em memória e publicação somente cifrada. Este documento é preenchido por etapas;
as seções de captura, orientação e ensaio no S25 entram nas tarefas 4.2 a 4.5.

## Dependências e permissões (tarefa 4.1)

CameraX fixada em **1.6.2** (publicada em 26/08/2026, estável) para `camera-core`, `camera-camera2`, `camera-lifecycle`
(em `:data-documents`) e `camera-view` (em `:feature-import`, só `PreviewView`). `camera-view` traz `camera-video` e
`camera-camera2-pipe` de forma transitiva; nenhum recurso de vídeo, áudio ou extensões é usado.

Manifesto do app:

- `android.permission.CAMERA` declarada; a solicitação em tempo de execução acontece somente ao escolher fotografar.
- `uses-feature` de câmera e de foco automático com `required="false"`: aparelhos sem câmera instalam e usam o seletor.
- Sem INTERNET, ACCESS_NETWORK_STATE, localização, microfone, armazenamento externo ou de mídia. As únicas outras permissões
  no manifesto mesclado vêm do WorkManager (WAKE_LOCK, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE) e da proteção de
  receivers do AndroidX, e já existiam.
- `minSdk` continua 24.

### Auditoria repetível

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:processDebugManifest
./gradlew :app:dependencies --configuration debugRuntimeClasspath > /tmp/deps.txt
python3 tools/verify_camera_integration.py \
  --manifest app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml \
  --dependencies /tmp/deps.txt
python3 -I -c "import sys; sys.path.insert(0,'tools'); import unittest; unittest.main(module='test_verify_camera_integration', argv=['x'])"
```

O script falha se: CAMERA estiver ausente, a câmera ou o foco forem obrigatórios, qualquer permissão fora da lista
permitida (rede, localização, microfone, armazenamento, mídia ou desconhecida) aparecer, `minSdk` mudar, qualquer artefato
CameraX divergir de 1.6.2, ou houver cliente de rede/telemetria (OkHttp, Retrofit, Ktor, Firebase, Play Services, gRPC,
ML Kit etc.) na árvore efetiva `debugRuntimeClasspath`. `tools/test_verify_camera_integration.py` cobre cada reprovação.

Resultado em 03/11/2026: **PASS** sobre o manifesto mesclado e a árvore efetiva atuais.

## Captura em memória e orientação (tarefa 4.2)

`CameraXPageCapturer` (`:data-documents`) usa a câmera traseira com `ImageCapture` em memória: nenhum arquivo, MediaStore,
FileProvider ou cache participa. O `ImageProxy` vira um `CapturedFrame` e `PageFrameProcessor` o **fecha em todos os
caminhos** (sucesso, formato inválido, falha de leitura, rejeição). Um quadro que chega depois do cancelamento é fechado e
descartado.

`CapturedPageNormalizer` produz o JPEG que será persistido:

- aceita só JPEG de até 20 MiB; rotação da câmera deve ser 0/90/180/270;
- orientação **uma única vez**, pela rotação da câmera. EXIF embutido, se existir, precisa concordar (1/6/3/8); estrutura
  ilegível ou orientação divergente é rejeitada (falha fechada);
- reduz para no máximo 2048 px no lado maior (`MAX_RASTER_SIDE`) mantendo a proporção, e regrava o JPEG (qualidade 92)
  **sem EXIF/GPS**. O pipeline existente lê orientação 1 e amostragem 1, então não gira de novo e prévia, OCR e regiões usam a
  mesma imagem persistida;
- rejeições usam códigos estáveis (`CAPTURE_EMPTY`, `CAPTURE_TOO_LARGE`, `CAPTURE_FORMAT_INVALID`,
  `CAPTURE_ROTATION_INVALID`, `CAPTURE_ORIENTATION_INCONSISTENT`, `CAPTURE_DECODE_FAILED`, `CAPTURE_MEMORY_LIMIT`,
  `CAMERA_UNAVAILABLE`, `CAPTURE_CAMERA_ERROR`), sem texto de imagem, metadados ou caminhos;
- os bytes de entrada são zerados após o uso; quem recebe o `NormalizedPage` é dono dele e chama `wipe()`.

Testes (`CapturedPageNormalizerTest`, instrumentados, 9 casos): posição de um marcador em cada rotação, ausência de
Exif/GPS/texto sintético na saída, consistência EXIF×rotação, limites e códigos, entrada zerada, a saída aceita pela
validação de transporte existente e decodificada sem segunda rotação, nenhum arquivo novo em cache/files/noBackup/externos, e
fechamento exato do quadro. Passaram em Android 7.0 (API 24), no emulador API 36 e no S25 em 03/11/2026.

Limite desta etapa: a câmera real ainda não foi exercitada (tarefa 4.5); o fechamento do `ImageProxy` do CameraX é
verificado pela interface `CapturedFrame`, não por hardware.

## Importação protegida de capturas (tarefa 4.3)

`ProtectedDocumentImporter.importCapture(page)` entra no **mesmo** `persist` do seletor e do corpus: bytes validados,
marcador no journal, cifragem pelo `EncryptedArtifactStore`, publicação dos metadados e remoção do marcador, com reconciliação
na reabertura. Não há URI simulada, arquivo temporário nem caminho alternativo em claro. A validação
(`CapturedDocuments.validate`) usa a mesma checagem de assinatura/truncamento/hash de qualquer JPEG importado. A página é
zerada em qualquer desfecho.

Testes (`CapturedImportTest`, instrumentados): hash do documento igual ao SHA-256 dos bytes; fonte cifrada que reabre em novas
instâncias e nenhuma sequência da imagem em claro em `files`, `cache`, `noBackupFiles` ou diretórios externos; falha de
persistência sem documento, journal ou fonte órfã; banco indisponível mantém a fonte cifrada e o marcador, e a reconciliação
posterior limpa sem duplicar; cancelamento antes de começar não publica nada; cancelamento durante a publicação resulta em
exatamente um documento íntegro (a seção de commit não é cancelável, como no seletor); bytes truncados são rejeitados antes de
qualquer journal; duas capturas viram dois documentos independentes.

Resultado em 03/11/2026: passaram em Android 7.0 (API 24), emulador API 36 e S25. No API 24, `ExtractionLimitsTest` e
`ProtectedDocumentImporterTest.lyingSizeCannotBypassActualByteLimit` (testes anteriores, não alterados) esgotam a heap do
emulador ao alocar 20 MiB; não ocorre no S25 nem no API 36.

## Fluxo prévia / usar / refazer / cancelar (tarefa 4.4)

Em **Enviar exame**, “Fotografar uma página” fica ao lado do seletor. A permissão CAMERA só é pedida ao tocar nessa ação
(`onCameraPermission`); negada, nada é criado, o seletor e o corpus continuam disponíveis e aparece uma mensagem
recuperável. Aberta a câmera, “Tirar foto” gera um candidato **só em memória** (`ImportViewModel`, nunca em estado
restaurável, `SavedStateHandle` ou arquivo); a conferência mostra a imagem já na orientação em que será salva, com **Usar
esta foto**, **Refazer** e **Cancelar foto**. “Usar” apenas escolhe a imagem como fonte e segue o caminho existente
(`importCapture` → execução no modo escolhido), sem confirmação clínica; o modo assistido exige o modelo preparado antes de
abrir a câmera.

Cada transição incrementa um número de **sessão**. Um resultado de captura que chega depois de refazer, cancelar, sair da
tela, perder a permissão ou iniciar outra sessão é zerado e descartado, sem criar estado, documento ou execução. Também
descartam o candidato: sair da tela (exceto rotação, em que o ViewModel mantém só a memória), revogação da permissão
(verificada em `ON_RESUME`) e o fim do ViewModel, que cobre a morte do processo antes de confirmar. Depois de confirmar, a
interrupção usa a recuperação do importador protegido (tarefa 4.3). Falhas mostram mensagens estáveis, sem texto interno.

Testes: `CaptureFlowTest` (14, JVM) cobre permissão negada, captura→usar com o modo escolhido, refazer, cancelar, resultado
tardio após sair/refazer/nova sessão/perda de permissão, revogação durante a conferência, morte antes de confirmar,
rejeições, câmera ausente, falha de persistência após confirmar (nada criado, página zerada), `usar` idempotente e modo
assistido sem modelo. `ImportControlsUiTest` (14, Compose) cobre as fases câmera e conferência, alvos de 48 dp, seletor
desabilitado durante a captura e ausência de aceite clínico. Passaram em Android 7.0 (API 24), no emulador API 36 e no S25
em 03/11/2026.

Limite: a câmera real e o `ImageProxy` do CameraX só são exercitados na tarefa 4.5; aqui a fonte de captura é simulada.

## Ensaio real no S25 e API 24 (tarefa 4.5)

**Fixture.** `tools/camera/make_camera_fixture.py` gera uma página sintética no layout `simple-v1` (cabeçalho, cinco
analitos, referência impressa na própria linha) em letra grande e alto contraste; valores esperados em
`tools/camera/camera-fixture.expected.json`, declarados como entrada e nunca derivados do app. Faixas arbitrárias, nenhum
dado de paciente.

**S25 (SM-S931B, Android 17), 04/11/2026, pacote separado `…validation.camera`, modo sem IA.** A página foi aberta na tela de
um computador e fotografada pela câmera traseira, com a permissão pedida na hora e concedida no aparelho. Fluxo completo:
captura → conferência → “Usar esta foto” → cópia privada → execução (“Todas as páginas foram processadas”, 1 de 1, 0 falhas).

| Campo | Página | Leitura do app | Estado |
| --- | --- | --- | --- |
| Glicose | 90 mg/dL, 70-99 | 90, mg/dL, 70-99 mg/dL | dentro da referência informada |
| Ureia | 52 mg/dL, 15-45 | `oe`, referência `15-45` sem unidade | precisa de revisão (unidade) |
| Creatinina | 0,4 mg/dL, 0,6-1,3 | `04`, `0,6-1,3 mg/dl` | precisa de revisão |
| Sódio | 140 mmol/L, 135-145 | 140, unidade ausente, `135-145 mmol` | precisa de revisão (ambiguidade) |
| Potássio | 4,2 mmol/L, 3,5-5,1 | 4,2, `mimol/l.`, `3,5-5, 1` | precisa de revisão (referência inválida) |

Resumo do app: 1 dentro da referência informada, 4 precisam de revisão, com “ambiguidades a conferir na fonte” e “conteúdo não
suportado”. O app **não classificou** nenhum campo mal lido: abstenção, como a governança exige.

**Fonte e orientação.** A imagem salva está em retrato, com o texto em pé e legível, sem segunda rotação. A cópia privada
reabre pela tela de resultados. O destaque do resultado do Potássio cai sobre o valor impresso (4,2) na imagem persistida, ou
seja, a região citada corresponde à imagem orientada que foi salva.

**API 24 e 36 com câmera.** Os dois emuladores têm câmera traseira emulada. `CameraXPageCapturerTest` (instrumentado) abre o
`CameraXPageCapturer` real, captura **duas vezes seguidas** (a segunda só funciona se o primeiro `ImageProxy` foi fechado) e
confere assinatura JPEG, limite de 20 MiB e 2048 px, orientação 1, ausência de EXIF/GPS, validação de transporte e que
nenhum arquivo novo foi criado (única tolerância: o marcador de 24 bytes `files/profileInstalled` do ProfileInstaller do
AndroidX, e nenhum arquivo novo contém assinatura de imagem). Passou nos dois.

**Registro:** `validation/results/camera-s25-2026-11-04.json`.

### Limites

- Uma foto, um aparelho, uma página, de tela para câmera (moiré, leve perspectiva, janela do computador visível). É um ensaio
  técnico de integração, **não** uma medida de acurácia e não autoriza afirmar ganho de acurácia por usar a câmera.
- A foto introduziu leituras erradas (3 de 5 valores numéricos e várias unidades/referências); o resultado é típico de foto de
  tela e não foi comparado com o caminho do seletor de arquivos. Papel impresso ou boa iluminação não foram avaliados.
- Cada captura confirmada cria um JPEG de uma página. **Não há montagem multipágina**; PDFs de várias páginas continuam pelo
  seletor.
- O modo assistido (IA) não foi usado neste ensaio.
