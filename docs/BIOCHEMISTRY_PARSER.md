# Parser dos layouts internos

`BiochemistryParser` em `:domain` recebe somente fragmentos posicionados do
documento, identificadores opacos e página. Não recebe gabarito, referências
externas, Android ou estado de outra execução. A versão é
`ptbr-biochemistry-layouts/1`; a avaliação continua em `REF_NUMERIC/2.0.0`.
O pipeline e a UI ainda precisam ser conectados nas tarefas seguintes.

## Escopo explícito

Reconhece os rótulos inteiros, sem diferenciar caixa/acentos:

| Analito | Rótulos/abreviações |
| --- | --- |
| Glicose | Glicose, Glicemia, GLI |
| Ureia | Ureia, Uréia, URE |
| Creatinina | Creatinina, CREA |
| Sódio | Sódio, Na, Na+ |
| Potássio | Potássio, K, K+ |

`GLIadina`, `CREA estimada`, `K/Na` e outros rótulos não são equivalências.
São suportados os dois templates internos de `tools/corpus/generate.py`:
`simple-v1` com referência na mesma linha e `footnote-v1` com ligação explícita
`Ver nota N` → `N.`. Não é um parser universal de layouts de laboratórios.

Exige um único cabeçalho com Analito/Resultado/Unidade/Referência nessa ordem.
O identificador impresso `Layout:` é respeitado; um identificador desconhecido
ou incompatível impede aceitar o layout. Sem identificador, cabeçalho e ligações
de notas distinguem esses mesmos templates. As quatro colunas têm bordas
normalizadas `[0, .265, .44, .59, 1]`, correspondentes às posições dos templates
600×800; caixas permanecem no raster comum da prévia. Uma região que cruza
colunas ou serve a mais de uma linha exige revisão. Não se escolhe pela faixa
que contém o resultado nem apenas pela menor distância.

## Fonte e abstenção

Cada campo conserva evidências com texto original, ID do span, página/região,
método/versão e confiança desconhecida null. Glifos PDF mantêm os separadores
do texto nativo; espaços não são inferidos das distâncias entre desenhos dos
caracteres. OCR fornece palavras e suas caixas. Fragmentos legados sem esse
metadado usam a separação geométrica conservadora. IDs de observação são
estáveis para os mesmos spans, execução e versão do parser.

Todas as faixas concorrentes são conservadas em `Observation.referenceCandidates`.
Contexto é aceito somente como rótulo literal `Contexto: ...` no documento,
comparado à condição explícita antes dos dois pontos de uma faixa. Não se
deduz grupo por idade, sexo, valor observado ou uma categoria adulta padrão.
Contexto ausente/conflitante, notas duplicadas e observações duplicadas
continuam exigindo revisão. Confirmação humana genérica não remove ambiguidades.

Faixas abertas/fechadas e unilaterais usam BigDecimal e comparadores explícitos.
Não há conversão nem herança automática de unidade entre faixas. Números ambíguos,
censura, faixas invertidas ou unidades incompatíveis passam pelos mesmos fallbacks
do avaliador. Referência ausente/qualitativo não vira resultado dentro da faixa.

Analitos desconhecidos, layouts não suportados e fragmentos sem posição ficam
explícitos em warnings/conteúdo não suportado. Conteúdo não vazio sem posição
também bloqueia a classificação automática das observações dessa página, pois
pode ser parte de um valor. Seu texto original é mantido, sem região inventada.

Evidências, candidatos e o campo completo `referenceRaw` são persistidos no payload AES-GCM existente,
com leitura compatível de payloads antigos sem essas chaves. Não houve mudança
do schema SQL v2. A transação rejeita candidatos de outra observação e evidências
fora das páginas conhecidas do documento. Saída automática permanece não revisada.

## Verificação técnica

Testes JVM cobrem os cinco analitos, abreviações, layouts, proveniência, separadores
de glifos, números/limites/comparadores, unidade, contexto ausente/conflitante,
rodapés, faixas concorrentes, duplicatas e tabelas densas. No Android, PDFs
sintéticos dos dois layouts passam por PDFium → fragmentos reais → parser →
avaliador, conferindo cinco observações e origem de cada campo sem alimentar
o parser com respostas esperadas. Reabertura protegida e busca de marcadores em
banco/journals/caches incluem os novos campos. Isso é evidência técnica sobre
fixtures sintéticas, sem validação clínica ou cobertura de documentos reais.
