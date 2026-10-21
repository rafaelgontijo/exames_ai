# Testes Android no ambiente cloud

Foi instalado Android Emulator 37.2.12 com a imagem `system-images;android-24;default;x86_64`, revisão 8. A aceleração KVM não está disponível; o emulador foi iniciado em software. Ele valida testes instrumentados de armazenamento/Keystore em API 24, sem representar desempenho ou hardware do Samsung S25.

O ambiente Android preparado fica em `/workspace/.android-dev/`, fora do repositório. Para repetir os testes em uma instância que contenha essa instalação:

```bash
source /workspace/.android-dev/env.sh
export ANDROID_AVD_HOME=/workspace/.android-dev/home/.android/avd
export ANDROID_EMULATOR_HOME=/workspace/.android-dev/home/.android
export XDG_RUNTIME_DIR=/tmp/exames-emulator-runtime
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
/workspace/.android-dev/sdk/emulator/emulator -avd exames-api24 -accel off -no-window -no-audio -no-snapshot -no-boot-anim -no-metrics -gpu swiftshader -cores 2 -memory 1536 -camera-back none -camera-front none
```

O emulador fica em primeiro plano nesse comando. Em outro terminal, com o mesmo ambiente SDK, aguardar `adb -s emulator-5554 shell getprop sys.boot_completed` retornar `1` e executar:

```bash
./gradlew :domain:test :data-local:testDebugUnitTest :data-local:connectedDebugAndroidTest :app:assembleDebug --no-daemon --console=plain
```

O host precisa permitir os sockets locais usados por ADB/emulador. Não alterar HOME para evitar diretórios sem permissão; os diretórios específicos acima ficam nas raízes de escrita do ambiente. Em uma instância nova será necessário instalar/provisionar a imagem e o AVD novamente se eles não tiverem sido preservados.

Os dados dos testes são sintéticos e as chaves de teste são removidas no teardown. Não declarar testes no aparelho físico, inferência LFM, matriz de 4 GB ou validação clínica a partir desse emulador.
