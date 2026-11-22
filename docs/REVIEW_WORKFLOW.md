# Correções, gabaritos e novas saídas

A revisão documental da bancada mantém o original e seus resultados. Não estabelece
validade clínica nem resolve contexto clínico desconhecido.

## Ações distintas

- **Corrigir extração** abre um campo com o último valor auditado como “Antes” e o
  rascunho como “Depois”. Salvar acrescenta `ManualCorrection` com ID e horário;
  não modifica a observação, avaliação, gabarito ou comparação da execução aberta.
  Um valor antigo obsoleto é rejeitado na transação. Referências usam o campo
  completo, inclusive alternativas concorrentes.
- **Criar reavaliação com correções salvas** cria outra execução vinculada à origem.
  A nova saída tem IDs próprios, regra atual, versão da projeção e lista exata dos
  IDs de correção aplicados. A base é `CORRECTED` quando há correções ou
  `REASSESSED` quando só se reaplica a regra. Não executa OCR/inferência, não
  inventa latência e registra `NOT_ATTEMPTED`. Modo/configuração da extração
  original permanecem como proveniência. O processador de extração rejeita estas
  saídas. Páginas incompletas da origem continuam incompletas.
- **Nova extração sem IA da fonte original** cria uma execução `AUTOMATIC` vinculada,
  com novos IDs e as versões atuais do pipeline. Lê novamente a cópia privada e
  executa o WorkManager. Não aplica correções ou gabaritos. O nome identifica
  explicitamente o modo sem IA; não é uma reexecução assistida implícita.
- **Revisar este gabarito em nova versão** abre o contrato JSON para autoria
  independente. A cópia recebe outra versão e começa `PENDING`, com revisor/data
  vazios. Campos, regiões, entradas canônicas e respostas esperadas continuam sob
  responsabilidade do autor; o app não preenche respostas com a extração ou com o
  avaliador. Para `REVIEWED`, informar `reviewerId` e `reviewedAt` ISO-8601. A
  revisão preserva caso e gabarito anterior, calcula SHA-256 dos bytes UTF-8 do
  contrato serializado e armazena `parentSha256` no envelope protegido. Os schemas
  compartilhados 1.0/1.1 não mudam. Uma versão existente diferente é rejeitada.
- **Comparar execução com gabarito versão …** seleciona o SHA exato e grava as
  medições ponta a ponta e isolada separadamente. Gabaritos pendentes continuam
  excluídos dos denominadores. Comparações anteriores e métricas automáticas
  permanecem ligadas às identidades originais. Relatórios separam `AUTOMATIC`,
  `CORRECTED` e `REASSESSED` e rejeitam base/IDs incompatíveis com a execução.

## Limites conservadores e privacidade

O mesmo avaliador determinístico avalia os campos projetados. Ambiguidades da
origem, censura, incompatibilidade de unidade e contexto desconhecido permanecem
em revisão. Digitar faixa ou selecionar aplicabilidade não estabelece associação
ou contexto ausente. Nenhuma conversão ou referência externa é criada. Os trechos
e regiões continuam pertencendo à fonte original, inclusive após editar valores.

A nova execução, todas as páginas e o estado terminal são gravados em uma única
transação Room. Uma falha em qualquer página desfaz a criação inteira. O banco
confere novamente os IDs de correção dentro dessa transação e rejeita mudanças
concorrentes. Os novos metadados ficam no payload AES-GCM já versionado; não há
novas colunas em claro nem migração estrutural.

Rascunhos ficam em `StateFlow` do ViewModel e usam o overload `TextFieldValue`
controlado pelo chamador, sem `rememberSaveable`, `SavedStateHandle`, rotas ou
DataStore. Reabrir a execução descarta o rascunho. Erros exibidos são fixos e não
incluem exceções ou conteúdo de exames. A exclusão do documento também remove
saídas vinculadas, correções, versões de gabarito e comparações.

O editor limita correções a 4.096 caracteres e gabaritos a 1 MiB UTF-8, 500
observações, 100 evidências por observação e 20 páginas. O passe JSON limita
profundidade/nós e rejeita chaves repetidas inclusive após escapes Unicode. O
contrato rejeita campos desconhecidos, IDs duplicados, decimais fora do formato,
metadados canônicos estrangeiros, páginas inválidas e revisão sem revisor/data.

## Verificação

Os testes de domínio cobrem limites, fallbacks, replay ordenado, propriedade de
correções, ausência de confirmação fabricada, fontes ativas/incompatíveis,
completude e autoria independente do gabarito. `ReviewWorkflowTest` verifica em
API 36 transação/rollback, concorrência, reabertura do banco, exclusão e métricas
originais preservadas. O controle Compose estende `tools/workbench/run_reopen.py`.
O ensaio no S25 permanece reservado para a etapa final.


Em 03/11/2026 passaram 245 testes JVM distintos (200 de domínio), 11
instrumentados selecionados e o controle Compose completo após force-stop em
PIDs 18013/18081. O Bundle foi verificado com ambos os tipos de rascunho abertos;
os dados originais e suas comparações permaneceram iguais. Relatórios:
`validation/results/review-workflow-emulator-2026-11-03.json` e
`validation/results/workbench-review-verified-emulator-2026-11-03.json`.
A limpeza dos pacotes criados está em
`validation/results/workbench-review-cleanup-2026-11-03.json`.

Relatórios, seleção explícita de pares e fluxo acessível estão documentados em
[CORPUS_IMPORT.md](CORPUS_IMPORT.md). O ensaio com TalkBack e fonte 200% passou
em emulador; isso não conclui o aceite físico do modelo.
