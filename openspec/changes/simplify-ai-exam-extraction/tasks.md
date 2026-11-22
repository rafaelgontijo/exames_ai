# Tasks

## 1. Extrator e prova inicial com o laudo autorizado

- [x] 1.1 Conferir o laudo real em ambiente privado e preparar a lista esperada por página, com nomes, resultados, unidades, referências e vínculos. Verificar que a lista cobre as seis páginas e que ela e o PDF ficam fora do Git, do APK, da CI e dos prompts.
- [x] 1.2 Implementar o novo contrato de saída da IA sobre texto posicionado do PDF. Testar schema inválido, trechos com IDs inexistentes e retenção de página e posição após segmentação. Documentar a configuração inicial fixada.
- [x] 1.3 Implementar a validação independente de texto e associação. Testar campo sem trecho, valor do bloco vizinho, referência condicional, resultado calculado, sobreposição e ocorrências repetidas. Usar testes de contrato sem laudos artificiais.
- [x] 1.4 Ligar a extração ao PDFium e ao LiteRT-LM sem passar pelo requisito de layout legado. Executar no S25 offline uma prova com uma página do PDF autorizado que antes falhou. Confirmar que o modelo publicou pelo menos um resultado e que o campo e sua região coincidem com a fonte. Registrar apenas a contagem e o estado técnico.

## 2. Extração completa e armazenamento privado

- [x] 2.1 Processar as seis páginas por blocos limitados, com junção por evidência, falhas por bloco, progresso e cancelamento. Testar que limites e falhas preservam resultados verificados e que respostas tardias não são publicadas. Documentar os estados.
- [x] 2.2 Ligar o resultado novo ao armazenamento cifrado e ao descarte da execução atual. Testar atualização com dados legados, reabertura e limpeza de fonte e derivados daquela execução. Verificar que navegação, logs e saved state não contêm dados clínicos.
- [x] 2.3 Executar o extrator completo com o laudo autorizado no S25 e comparar com a lista privada antes de avançar para o aceite final. Corrigir omissões e associações incorretas observadas. Registrar acertos, omissões, extras, ambiguidades e falhas apenas como totais, além da configuração e dos limites atingidos.

## 3. Interface focada na extração

- [x] 3.1 Substituir a navegação por entrada, andamento e resultados. Verificar em teste de UI a ação única `Selecionar PDF`, cancelamento, arquivo não suportado, vazio e falha.
- [x] 3.2 Exibir resultados em ordem documental, referência quando vinculada e `Ver no laudo`. Conferir que regiões, avisos de incerteza e falhas parciais correspondem ao estado do extrator. Verificar rolagem com fonte ampliada e semântica acessível.
- [x] 3.3 Remover da distribuição a interface de bancada e os componentes exclusivos de histórico navegável, corpus, avaliação referencial, câmera, áudio e compartilhamento. Verificar manifesto, rotas e dependências da variante distribuída e atualizar o README para o fluxo único.

## 4. Aceite no S25

- [x] 4.1 Executar `./gradlew assembleDebug test` e validar instalação limpa offline da variante candidata no S25. Inspecionar o APK e comprovar modelo fixado, ausência de dados do laudo e de permissão de internet.
- [x] 4.2 Selecionar o PDF real no fluxo final e conferir as seis páginas com a lista privada. Confirmar correspondência de todos os resultados inequívocos e preservação das incertezas, sem itens inventados ou vinculados ao bloco incorreto. Verificar destaque, cancelamento, seleção de outro PDF e descarte. Registrar métricas agregadas e estado do aceite.
