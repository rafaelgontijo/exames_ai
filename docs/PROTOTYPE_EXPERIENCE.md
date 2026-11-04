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

## Pendente de tarefas posteriores

A matriz de rotas e as capturas sintéticas (claro/escuro, fonte 200%, TalkBack) entram na tarefa 3.5.
