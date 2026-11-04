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
