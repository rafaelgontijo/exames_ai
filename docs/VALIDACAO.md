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
