# Importação de pacotes e relatório do revisor

Na importação, selecionar **Pacote do corpus** e um ZIP schema 1.0 ou 1.1.
O pacote inteiro é validado em memória antes da publicação: caminhos seguros,
entradas únicas, ausência de symlinks/criptografia, tamanho e hash de cada fonte,
contratos de gabarito, proveniência e partições. Limites: ZIP/expansão de 128 MiB,
2.500 entradas, 20 MiB por arquivo e 2 MiB por JSON. Nenhum caminho do ZIP é
extraído para o filesystem. Hashes conferem integridade, não autenticidade do autor.

Cada documento e gabarito são publicados atomicamente no repositório protegido;
o processamento é agendado pelo Hilt/WorkManager. Uma interrupção pode deixar
casos completos já importados: o contador e o histórico permitem identificá-los.
Não há transação única para todos os documentos. Textos e gabaritos não entram
nas rotas, SavedStateHandle ou preferências. Respostas do gabarito não alimentam
a extração. Somente pacotes sintéticos/desidentificados são autorizados.

No histórico, abrir uma execução e selecionar o gabarito revisado pelo hash.
**Gerar relatório** mostra contagens, denominadores, cobertura, abstenção,
classificação, erros fora→dentro e avaliação isolada, preservando N/A para conjuntos
vazios. Estratos e identidade técnica podem ser expandidos. Gabaritos pendentes
não entram nos denominadores; saídas automáticas, corrigidas e reavaliadas são
separadas. Correções e revisões seguem `REVIEW_WORKFLOW.md`.

Para A/B, selecionar explicitamente uma execução automática sem IA e outra
assistida do mesmo documento, gabarito e versões compatíveis. O relatório inclui
novos erros mesmo quando a cobertura aumenta. Sucesso do parser não conta como
uso aceito do modelo. Os limites das medições estão em `PERFORMANCE_MEASUREMENTS.md`.

O controle `tools/workbench/run_corpus.py` verificou oito casos (quatro formatos,
dois layouts), TalkBack real, fonte 200%, foco em título, rolagem e alvos de 48 dp.
O teste JVM de contraste cobre os temas claro/escuro. Estados possuem texto além
da cor. Evidência: `validation/results/corpus-report-a11y-verified-emulator-2026-11-03.json`.
O controlador restaura as preferências e remove somente os pacotes descartáveis
que criou. Acessibilidade em emulador não substitui o ensaio físico no S25.

O seletor de fixtures técnicas agora usa somente DEVELOPMENT. Os primeiros casos
HELD_OUT usados em diagnóstico têm exposição registrada em `CORPUS.md`; seus
resultados não representam um conjunto de teste intocado. Gabaritos, hashes e
partições originais são preservados.
