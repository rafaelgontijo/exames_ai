# Revisão visual com evidência (7.2)

## Campos, avaliação e revisão documental

Cada observação mostra textos brutos, avaliação/motivo e regra original.
O estado documental é separado: ausência de revisão, confirmação registrada ou
correções append-only não substituem a avaliação. NEEDS_REVIEW e NOT_CLASSIFIABLE
continuam explícitos mesmo com revisão registrada. Abrir uma evidência não
confirma, corrige, reavalia ou altera métricas.

Gabaritos mostram versão, estado documental, campos, estado esperado e motivo
conhecido. PENDING continua identificado como fora das métricas de acerto.
Os controles distintos de edição, gabarito e reavaliação estão descritos em
[REVIEW_WORKFLOW.md](REVIEW_WORKFLOW.md) (7.3).

## Selecionar a fonte

Os campos com `ExtractedFieldEvidence` oferecem seleção por campo/página. A
seleção original conserva todas as regiões daquele campo naquela página,
incluindo referências concorrentes. A seleção do gabarito usa sua versão e
observação próprias, com identificação diferente na prévia. A tela apresenta o
texto bruto do campo ou os trechos brutos citados e desloca a lista para a fonte.

O bitmap vem da mesma cópia privada autenticada usada no processamento. Retângulos
são projetados normalizando os limites pelas dimensões registradas da página.
As coordenadas já pertencem ao viewport orientado de PDF/OCR/EXIF; não se aplica
rotação ou espelhamento novamente. A imagem ocupa uma caixa com a proporção do
bitmap, e o Canvas usa essa mesma caixa. Contorno e preenchimento translúcido
não alteram o bitmap original, e o desenho é limitado à área da página.

A projeção admite no máximo dois pixels de arredondamento de dimensão. Se algum
trecho da seleção usa frame incompatível, página errada ou limites que colapsam
na precisão de desenho, nenhum trecho é destacado. A página e o aviso continuam
visíveis. O sistema não estima caixas a partir de texto ou posição presumida.
Registros legados sem evidência continuam abrindo a página inteira.

Quando o texto de referência legado existe somente na tabela de referências,
a tela usa esse registro preservado. Candidatos sem seleção são identificados
separadamente. Exibir esses dados não recalcula a avaliação original nem cria
coordenadas ausentes.

A seleção só aceita evidência pertencente às observações/gabaritos carregados.
Página fora do documento não chama o decoder. Cada pedido de prévia recebe uma
geração; trocar de campo, fechar, trocar de execução ou excluir invalida callbacks
anteriores, inclusive falhas tardias na mesma página. Não há prévia clínica em
cache persistente ou conteúdo de seleção em rotas/SavedStateHandle/Bundle.

## Diferenças salvas

A tela mostra valores esperado/obtido registrados e fontes disponíveis por
campo. O gabarito deve coincidir em caseId, versão e hash. O vínculo da diferença
à observação original exige runId e versões atuais de comparador/parser; usa o
mesmo pareamento por analito, página e evidência. Valores clínicos esperados não
selecionam a linha. Ambiguidade entre duplicatas e versões não verificadas ficam
sem vínculo automático com a fonte extraída. O revisor pode conferir os campos
e páginas apresentados acima. Comparações antigas não são regravadas.

## Verificação

Testes JVM cobrem escala proporcional, bordas, quatro orientações já aplicadas,
frame incompatível, arredondamento, perda de precisão, preservação de referências
concorrentes, separação original/gabarito, falhas tardias e associação por versões/
hash sem usar valores. Quatro testes Compose no emulador API 36 verificam pixels
do contorno e áreas externas, fallback sem destaque parcial, revisão documental
separada de abstenção e gabarito revisado ainda exigindo revisão contextual.

O controle da bancada usa PDF privado real e seleciona a referência extraída e a
referência declarativa do gabarito após force-stop/URI revogada. Verifica desenho,
recriação da Activity sem conteúdo clínico salvo e exclusão recuperável. Esse
controle reutiliza `tools/workbench/run_reopen.py`; os comandos estão em
`WORKBENCH_FLOW.md`. O provedor e o histórico de revisão são fixtures sintéticas,
não uma medição de acurácia ou validação clínica.

Não houve inferência real, teste no S25 ou nova inspeção física em kernel de
16 KiB. Os testes de orientação do Canvas usam bitmaps sintéticos; os controles
de PDF/OCR/EXIF do adaptador estão documentados em `DOCUMENT_EXTRACTION.md`.
TalkBack, fonte ampliada, relatórios e acessibilidade completa permanecem em 7.5.

Em 03/11/2026 passaram build padrão, **223 testes JVM distintos (184 no domínio)**
e os quatro controles Compose, sem falhas/ignorados. O controle real da bancada
passou em PIDs **15683/15751**, com destaque extraído/gabarito e remoção dos pacotes
de validação criados pelo ensaio. Evidência consolidada:
`validation/results/evidence-review-emulator-2026-11-03.json`; fluxo completo:
`validation/results/workbench-evidence-final-emulator-2026-11-03.json`.
