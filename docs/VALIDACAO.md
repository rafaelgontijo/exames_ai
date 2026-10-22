# Validação técnica — 21/10/2026

Executado no ambiente cloud com JDK 21, Gradle Wrapper 8.13 e SDK Android 36:

```bash
./gradlew assembleDebug test :app:assembleDebugAndroidTest --no-daemon --console=plain
```

Resultado: **BUILD SUCCESSFUL**, 422 tarefas (132 executadas e 290 atualizadas).

- Todos os oito módulos compilados, incluindo o experimental isolado.
- 64 testes JVM: 23 de parser numérico e 41 de avaliação determinística; zero falhas, erros ou testes ignorados.
- APK de debug gerado: `app/build/outputs/apk/debug/app-debug.apk`.
- APK instrumentado de navegação Compose compilado, mas não executado. Requer aparelho/emulador.
- Manifest mesclado verificado: sem INTERNET e `allowBackup=false`.
- Estrutura de módulos, manifests XML e ausência de imports Android em `:domain` verificados.
- Wrapper executado com sucesso; distribuição fixada e verificada por SHA-256.

Avisos não bloqueantes: bibliotecas nativas empacotadas sem strip de símbolos e opções Hilt/Kapt não consumidas no source set de testes instrumentados.

Esta validação é de scaffolding e regras numéricas com fixtures sintéticas. OCR, operação offline em aparelho, acessibilidade, memória, segurança do armazenamento, exclusão após falhas e validação clínica ainda exigem testes próprios. A CI foi configurada, mas não foi executada no GitHub nesta sessão. Ver pendências no README.


## Bancada de validação — persistência v2

Execução em 21/10/2026, JDK 21, SDK 36, minSdk 24 aprovado pelo usuário:

```bash
source /workspace/.android-dev/env.sh
./gradlew assembleDebug test :data-local:connectedDebugAndroidTest --no-daemon --console=plain
```

Resultado: **BUILD SUCCESSFUL**, 438 tarefas Gradle (266 executadas, 172 atualizadas).

- 72 testes de domínio JVM aprovados.
- 5 testes do codec AES-GCM aprovados em cada variante JVM debug/release.
- 12 testes instrumentados aprovados no emulador Android 7.0/API 24 x86_64: Keystore, schema, migração/recuperação, reabertura, versões/correções, commit atômico, privacidade e limites de associação.
- A inspeção ampliada dos diretórios privados de cache/files/noBackup/databases foi verificada em execução instrumentada adicional direcionada, também aprovada.
- APK debug gerado; aapt confirmou minSdk 24 e targetSdk 36. Todos os sete módulos Android declaram minSdk 24.
- Hilt e worker de limpeza usam ProtectedDatabaseFactory; não há outra abertura Room nem fallback destrutivo no código de produção.
- Os marcadores sensíveis sintéticos não apareceram em claro no banco, journals ou diretórios privados examinados.
- OpenSpec passou na validação estrita; 14/51 tarefas estão concluídas. As tarefas restantes não foram marcadas como completas por esses checks.

A execução adicional de privacidade usou:

```bash
./gradlew :data-local:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=br.com.exames.datalocal.ProtectedRepositoryTest#persistedFilesAndJournalsDoNotContainSensitiveMarkers' --no-daemon --console=plain
```

O corpus contém 216 variantes sintéticas/1080 observações, com validação de pacote aprovada. Foram aprovados os 15 testes iniciais da suíte Python e o teste adicional direcionado de compressão excessiva, totalizando 16 casos de teste distintos. A geração em duplicata produziu o mesmo manifesto e ZIP. Ver docs/CORPUS.md.

**Limites dessa execução:** não houve execução física no S25, extração de laudos reais, OCR ponta a ponta, validação clínica ou inferência do modelo. A UI e o pipeline continuam pendentes. A obtenção do peso LFM estava bloqueada nessa etapa; o bloqueio foi superado na retomada abaixo. O runtime/modelo ainda não está incluído no APK. Não declarar o MVP completo.

## Retomada — verificação do peso LFM

Em 21/10/2026, a branch foi recuperada no commit remoto
`6a8f0528a8c6da2f77bec716a4cc8959c1794164`. Git e Hugging Face funcionaram
com acesso à rede autorizado no executor, preservando o proxy e TLS.

- Download completo: 736015744 bytes, HTTP 200.
- SHA-256 calculado no binário: `da3c0ad154b78f33537b117fbe747cc1d4fd3507479002b159998f4023f32a84`.
- Tamanho/hash coincidem com o pin, metadados LFS e manifesto de origem na revisão `f8b5b3a3eea2e3859e821ad9d9222c64d649eff7`.
- Peso mantido em `/workspace/.model-cache/`, fora do Git. Manifesto fixado e licença preservados em `validation/models/lfm2.5-1.2b-thinking/`.
- Tarefa 4.6 concluída: 15/51 tarefas. Os resultados anteriores de build/testes não foram reexecutados nesta alteração documental.

Ver comandos, termos de redistribuição e alternativa Qwen em `MODEL_DOWNLOAD.md`.
O ADB não lista dispositivos conectados nesta retomada. A tarefa 4.7 continua
pendente do spike real no S25; download e integridade não comprovam inferência,
JSON final, memória, latência, reset ou cancelamento. Nenhum benchmark foi executado.
