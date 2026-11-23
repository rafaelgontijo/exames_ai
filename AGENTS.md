# Governança

App Android nativo para extrair resultados de PDFs digitais de exames com IA local e mostrar a evidência no laudo.

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
- A distribuição padrão extrai resultados com LiteRT-LM e Gemma 4 E2B no aparelho. A bancada e seus modos de comparação ficam no build técnico opcional.
- O modelo propõe campos sustentados por evidência do documento. Validar schema, trechos, posição e associação antes de exibir os resultados. Preservar as incertezas por campo e por página.
- Usar Engine nova por solicitação até comprovar reset seguro. Validar somente o canal final; não persistir/exibir raciocínio interno. Pesos fixados por revisão/hash ficam fora do Git e são distribuídos para primeira execução offline.
- Ao integrar ou distribuir inferência, consultar `validation/models/selection.json` e `docs/LITERT_COMPATIBILITY.md` para a configuração selecionada e os limites comprovados.

## Design e criação de telas (Stitch)
- Usar o projeto Stitch **`Meu Exame — MVP`** (`projectId`: `2627731492696541504`, Design System: `assets/16423656734733007448`), derivado do protótipo visual `App de Exames — MVP.html`, como base oficial para criar e evoluir telas do aplicativo.
- Consultar `.stitch/DESIGN.md` e `.stitch/metadata.json` antes de gerar ou editar telas no Stitch ou implementar componentes em Jetpack Compose.
- Ao gerar ou variar telas via Stitch MCP, informar `projectId: "2627731492696541504"`, `designSystem: "assets/16423656734733007448"` e `deviceType: "MOBILE"`. Descrever no prompt apenas estrutura, fluxo e conteúdo, sem duplicar tokens de cor ou fonte já definidos no Design System.
- Manter os limites éticos e legais em todas as telas (no Stitch e no app): nunca incluir diagnósticos, recomendações clínicas, prazos/urgência, especialidades ou encaminhamentos (ex.: UBS); priorizar processamento offline no aparelho, referências brutas do documento, fallback `NEEDS_REVIEW` e verificação de evidência (`Ver no laudo`).
- Na implementação Android nativa, mapear o visual das telas do Stitch para os tokens de `:core-ui` (`ExamesTheme`, `ExamesTokens`, `ExamesTypography` com Figtree local offline) e seguir `docs/PROTOTYPE_EXPERIENCE.md` (temas claro/escuro, contraste ≥ 4,5:1 em texto e ≥ 3:1 em controles, alvos ≥ 48 dp e escala de fonte até 200%).

## Convenções e verificação
- Branches: `feat/<descricao>`, `fix/<descricao>`, `chore/<descricao>`.
- Commits semânticos: `feat:`, `fix:`, `test:`, `docs:`, `chore:`.
- JDK 17 ou 21, Android SDK 36; `local.properties` não versionado.
- Build: `./gradlew assembleDebug`. Testes: `./gradlew test`.
- Testes de domínio: `./gradlew :domain:test`; instrumentados: `./gradlew connectedDebugAndroidTest` em dispositivo disponível.
- Para a change simplify-ai-exam-extraction, usar o laudo real autorizado como única fonte de cobertura e aceite fim a fim. Testes locais de contratos podem usar dados mínimos de tipos e limites, sem laudos artificiais. Ensaios da bancada histórica permanecem separados.
- Um titular pode autorizar explicitamente um ensaio técnico local com o próprio exame. Usar um pacote de teste separado, aparelho offline e o importador privado. Transferir em memória e manter a cópia persistida apenas cifrada. Registrar somente contagens, estados e códigos operacionais. Manter o original e os derivados identificáveis fora do Git, APKs, CI, capturas, logs e relatórios. A autorização de importação não abrange áudio, compartilhamento ou uso como fixture.
- Não declarar validação clínica a partir de testes técnicos.
