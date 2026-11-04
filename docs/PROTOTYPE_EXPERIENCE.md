# Experiência “Meu Exame” (adaptação do protótipo)

Referência visual: `App de Exames — MVP.html` (594017 bytes, SHA-256
`7e43f41ac087a1669955693d5d7cf4e8899ca6691fcc721c6180bcaf01202756`). O arquivo permanece **não versionado e
inalterado**; o hash foi conferido novamente em 03/11/2026. Seu JavaScript, fontes web e textos clínicos não entram no
app. Conferência feita por leitura (Python `-I`, sem escrita) do manifesto do bundle: quatro páginas HTML de
390 × 844 px (“Início”, “Enviar exame”, “Resultado”, “Orientação”).

## Tokens conferidos no HTML

| Uso | HTML | Token Compose (claro) | Observação |
| --- | --- | --- | --- |
| Fundo | `#f4f8fd` | `background` | igual |
| Texto | `#13233a` | `onBackground`/`onSurface` | igual |
| Texto secundário | `#4a5b72` | `secondaryText`, `onSurfaceVariant` | igual |
| Primária | `#1f5fbf` | `primary` | igual |
| Títulos | `#0f3a7a` | `title`, `onPrimaryContainer` | igual |
| Borda decorativa | `#d6e6f8` | `decorativeBorder`, `outlineVariant` | só decoração |
| Chip/área azul | `#dceafb`, `#e8f1fc` | `infoContainer`, `surfaceVariant` | igual |
| Chip de atenção | `#fff2d9` / `#5c3600` | `reviewContainer`/`onReviewContainer` | igual; significa revisão, não urgência |
| Controles interativos | — | `controlBorder` `#6b7f99` (3:1) | **adaptação**: o HTML usa só `#d6e6f8` |
| Alerta | `#ff8a80` etc. | `alertContainer` | **adaptação** com texto; não indica urgência |
| Fonte | Figtree 400–800 | Figtree estática local | `core-ui/third_party/figtree` |
| Cartões/botões | raio 16/20, botão 52–60 | `Shapes` 12/16/20 dp, ações ≥ 52–56 dp | alvos ≥ 48 dp |

O HTML não define tema escuro; os equivalentes escuros são explícitos em `ExamesTokens` e medidos por
`ThemeContrastTest` (texto 4,5:1, controles 3:1). Tamanhos de texto 13–26 estão em sp.

## Correspondência das quatro telas

| Tela do protótipo | Tela nativa | Dados reais e substituições |
| --- | --- | --- |
| Início (“Olá! Vamos entender o seu exame?”, “Meus exames”) | **Início** (aba) | Documentos importados, formato genérico e data de importação, última extração automática por modo. Sem “Exame de urina/sangue”, datas ou “2 alterados/Tudo normal” do HTML. |
| Enviar exame (foto, PDF/imagem, dicas, privacidade) | **Enviar** (fluxo) | Câmera privada e SAF/corpus, modos e fallback existentes. “Autorizo o uso…” vira aviso de processamento neste aparelho; não é barreira clínica. “Tirar foto” só seleciona a fonte. |
| Resultado (“Seus resultados”, “2 resultados pedem atenção”) | **Resultado** (fluxo) | Estados referenciais com motivo, completude, base/modo e participação real da IA; Ouvir/Compartilhar/Revisar. “Tudo normal” → “dentro da referência informada”. |
| Orientação (“O que fazer agora”, especialidades, UBS, pronto atendimento) | **Ajuda** (aba) | Ajuda de revisão documental e privacidade. Removidos urgência, prazos, especialidades, doenças, UBS e “compartilhar com o médico”. |

Navegação inferior: Início, Exames, Ajuda. “Onde ir” do protótipo vira Ajuda de revisão; “Exames” abre o histórico/bancada
existente. Detalhes de bancada (fonte, correções, gabaritos, versões, relatórios, exclusão) permanecem acessíveis.

## Substituições de linguagem

| Do protótipo | No app |
| --- | --- |
| Tudo normal / está bom | dentro da referência informada (nunca garantia) |
| N alterados / pedem atenção | contagens separadas: abaixo, acima, requer revisão, não classificável |
| Procure atendimento / UBS / especialidades | conferir a fonte e, se necessário, conversar com quem pediu o exame (sem prazos) |
| Entenda cada resultado | resultado com referência do próprio documento |

## Matriz de rotas

Rotas carregam somente nomes fixos ou o ID opaco da execução (`ProcessingRunIds`); nenhum texto clínico, valor ou URI.

| Rota | Tela | Barra inferior | Origem → destino | Voltar |
| --- | --- | --- | --- | --- |
| `home` | Início | sim | inicial; Enviar, Resultado, Exames | sai do app |
| `exams` | Exames (histórico/bancada) | sim | aba; Início, Ajuda, Enviar (“Histórico local”) | volta ao Início |
| `help` | Ajuda | sim | aba; Exames (“Abrir lista de exames”) | volta ao Início |
| `send` | Enviar exame | não | Início → Enviar; abre Resultado ou Exames | volta à origem |
| `results/{runId}` | Resultado | não | Início, Exames ou Enviar → Resultado; “Abrir execução de origem” | volta à origem |

Recriação da Activity restaura apenas o destino; rascunhos, imagens e prévias não são salvos. `ShellRoutesTest`,
`ResultRoutesTest`, `NoRestorableClinicalStateTest` (nenhum `SavedStateHandle`/`rememberSaveable`/`Bundle` em código de
produção) e `NavigationTest` cobrem esse contrato.

## Verificação das quatro telas (tarefa 3.5)

`:ui-checks` (módulo só de testes, sem o APK de 2 GB) renderiza Início (vazio e com exame), Enviar, Resultado e Ajuda nos
temas claro/escuro com fonte 100% e 200%, usando o mesmo fundo temado da `MainActivity`, e verifica: checagens de
acessibilidade do Compose (`enableAccessibilityChecks`), altura e largura de layout de todo controle ≥ 48 dp e alcance por
rolagem (telas de rolagem simples). Passou em 03/11/2026 em Android 7.0 (API 24), no emulador API 36 e no S25.

Capturas sintéticas (sem dado clínico; documento `doc-sintetico`, valores fictícios) em `docs/prototype-screens/`,
nomeadas `<tela>-<claro|escuro>-<100|200>.png` (20 arquivos, geradas no Samsung S25, SM-S931B, Android 17, em 03/11/2026).

Defeitos encontrados e corrigidos por essa verificação: botões de texto com 40 dp (agora `ExamesTextButton`, ≥ 48 dp) e
raiz do app usando `surface` em vez de `background`, que deixava telas sem fundo próprio claras no tema escuro.

### Limites

- **TalkBack real não foi executado.** As checagens acima são automatizadas (ATF/semântica); a ordem de leitura e os rótulos
  falados no TalkBack do S25 ainda precisam de sessão manual.
- As capturas são sintéticas (host de teste com estado fictício), não do app com dados reais.
- Nos módulos de biblioteca o APK de teste herdava `targetSdk` 24 e o S25 (Android 17) exibia o aviso de app antigo sobre os testes; `testOptions.targetSdk = 36` corrige isso.
- `EvidenceUiTest.overlayPaintsTheCited…` usa `captureToImage`, que não existe em API 24 (`PixelCopy`); falha só ali, desde a
  change anterior, e não indica defeito do app.
