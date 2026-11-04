# Aceite físico restante

A configuração selecionada continua Gemma 4 E2B GPU, LiteRT-LM 0.18.1, pins em
`validation/models/selection.json` e `gemma4-e2b/model.lock.json`. Os controles
históricos do adaptador anterior não aprovam o novo prompt/processo de produção.
O protocolo v5 e o aceite completo passaram no S25, com 1/8 páginas aceitas pelo
modelo e sete fallbacks. `PRODUCTION_PROTOCOL_ACCEPTED` está `true` somente para
o aparelho de referência. Evidências e limites em `S25_FINAL_VALIDATION.md`.
As instruções abaixo reproduzem os controles com novos relatórios e pacotes
inicialmente ausentes; não descrevem trabalho ainda pendente nesta versão.
Os scripts recusam emulador, outro modelo de aparelho, pacote já instalado e
relatório existente. Nunca instalar o pacote padrão para esses controles.

Requisitos: S25 SM-S931B, depuração USB autorizada, tela desbloqueada, modo avião
e Wi-Fi desligado **antes da instalação**. O controlador somente consulta essas
preferências; não as altera. Confirmar o serial com `adb devices -l`. Pesos ficam
no cache externo conforme `BUNDLED_INFERENCE.md` e `OCR_COMPATIBILITY.md`.

```bash
./gradlew assembleDebug :app:assembleDebugAndroidTest \
  -PvalidationApplicationId=br.com.exames.offline.validation.native \
  --no-daemon --console=plain
python3 tools/workbench/run_native_acceptance.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial SERIAL_DO_S25 \
  --report validation/results/native-production-s25-NOVA_DATA.json
```

Esse ensaio exige JSON de duas linhas sintéticas pelo protocolo real, schema e
spans aceitos, filhos distintos com prefixo comum, canal final exclusivo, entradas
maliciosas protegidas, limites de bytes/contexto e aposentadoria após cancelamento
nativo. Se falhar, conservar o relatório e corrigir a causa antes de ativar a UI.
Não aceitar fallback exclusivo como resultado suficiente. O controlador remove
apenas os pacotes que criou, após sucesso; em falha deixa o pacote para diagnóstico.

Somente após sucesso, atualizar a evidência e o sinal de aceite na configuração,
compilar novamente o pacote descartável final e executar:

```bash
./gradlew assembleDebug :app:assembleDebugAndroidTest \
  -PvalidationApplicationId=br.com.exames.offline.validation.final \
  -PexamesCorpusPath="$PWD/validation/generated-v2/corpus.zip" \
  -PexamesCorpusPython="$PWD/tools/corpus/.venv/bin/python" \
  --no-daemon --console=plain
python3 tools/workbench/run_final_acceptance.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial SERIAL_DO_S25 \
  --report validation/results/final-workbench-s25-NOVA_DATA.json
```

O pacote contém oito casos sintéticos (PDF digital/escaneado, JPEG e PNG, dois
layouts). Mede pares automáticos, exige alguma página realmente aceita pelo
modelo, exporta métricas/estratos, corrige um campo sem modificar os originais,
força parada, reabre a fonte privada sem URI externa e exclui os oito documentos
preservando pesos estáticos. Não representa todos os 216 cenários/variantes nem
cobertura pública ou validação clínica. Tokens por canal, pico real/GPU e hardware
de 4 GB/kernel 16 KiB mantêm os limites de `PERFORMANCE_MEASUREMENTS.md`.

Após os controles, gerar o APK padrão com `./gradlew assembleDebug test
:app:assembleDebugAndroidTest` e repetir a auditoria de artefato. Não editar
checkboxes de execução física com base apenas em compilação ou teste simulado.
