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
