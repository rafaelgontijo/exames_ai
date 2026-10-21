# Governança

App Android nativo offline-first para análise referencial determinística de exames laboratoriais.

## Limites éticos e legais
- O app não fornece diagnósticos nem recomendações clínicas ou urgência.
- Nunca afrouxar o fallback NEEDS_REVIEW para dados ambíguos, unidades incompatíveis ou contexto desconhecido.
- Nunca inventar referências; preservar textos brutos e proveniência. Confirmação humana não elimina ambiguidade clínica.

## Código e privacidade
- `:domain` é Kotlin/JVM puro, sem Android SDK.
- Não importar Retrofit, Ktor, OkHttp, telemetria ou dependências de rede no MVP. Não adicionar permissão INTERNET.
- Toda nova regra em `:domain` exige testes unitários de limites e fallback.
- Proibido registrar conteúdo de exames, nomes, valores clínicos ou URIs em logs de produção, analytics ou crash reports.
- Manter backups desabilitados; arquivos clínicos persistidos são privados e criptografados com chaves no Android Keystore.
- DataStore armazena apenas preferências gerais sem PII. Não salvar conteúdo clínico no SavedStateHandle.
- Não habilitar inferência experimental por padrão ou torná-la dependência dos fluxos do MVP.

## Convenções e verificação
- Branches: `feat/<descricao>`, `fix/<descricao>`, `chore/<descricao>`.
- Commits semânticos: `feat:`, `fix:`, `test:`, `docs:`, `chore:`.
- JDK 17 ou 21, Android SDK 36; `local.properties` não versionado.
- Build: `./gradlew assembleDebug`. Testes: `./gradlew test`.
- Testes de domínio: `./gradlew :domain:test`; instrumentados: `./gradlew connectedDebugAndroidTest` em dispositivo disponível.
- Usar somente fixtures sintéticas/desidentificadas. Não declarar validação clínica a partir de testes técnicos.
