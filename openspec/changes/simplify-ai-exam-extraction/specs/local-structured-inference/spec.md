# Spec Delta

## Purpose

Extrair resultados de exames em PDFs digitais com IA local, com evidência rastreável em cada campo publicado e incertezas visíveis para conferência.

## ADDED Requirements

### Requirement: AI extracts results from positioned PDF evidence

O app SHALL usar IA no aparelho para propor resultados a partir do texto posicionado do PDF digital. A publicação SHALL depender de validação independente contra a fonte. Uma página com texto legível SHALL chegar à IA independentemente de o parser legado reconhecer seu layout.

#### Scenario: Extract a report without the legacy table headers
- **WHEN** o PDF autorizado é selecionado e uma página contém resultados em blocos diferentes das tabelas legadas
- **THEN** a IA propõe campos a partir desses blocos e o app exibe os resultados cuja evidência verifica de forma independente

#### Scenario: Preserve an explicitly printed calculated result
- **WHEN** o laudo contém uma medida direta e um resultado calculado com nome próprio
- **THEN** os dois conservam seus rótulos e vínculos distintos. O app não equipara uma estimativa à medida direta nem calcula novo resultado

### Requirement: Published fields retain their document association

Cada campo publicado SHALL trazer o texto verificável e a região da fonte. O app SHALL preservar nomes, valores, comparadores, unidades e referências como impressos quando o vínculo é inequívoco. Campos ausentes ou com vínculo incerto SHALL ficar em branco ou sinalizados para conferência. A extração SHALL excluir identificação do paciente, conteúdo administrativo e interpretação clínica.

#### Scenario: A valid literal is cited from a neighboring result
- **WHEN** a IA cita um número que existe, mas pertence a outro bloco, resultado anterior ou referência
- **THEN** o app não apresenta esse número como resultado verificado e sinaliza a associação para conferência

#### Scenario: A laboratory range is conditional
- **WHEN** o laudo mostra referências para contextos diferentes
- **THEN** o app exibe somente referências verificáveis com seus qualificadores e não escolhe qual se aplica ao paciente

### Requirement: Partial results distinguish verified output from failures

A extração SHALL tratar falhas por página ou bloco e manter campos já verificados. Páginas sem texto, processamento interrompido e saída sem evidência SHALL ter estados visíveis. Nenhum caminho sem IA SHALL produzir resposta apresentada como extração da IA.

#### Scenario: Model reaches its context limit
- **WHEN** uma página não cabe no contexto de uma tentativa
- **THEN** o app processa blocos limitados ou sinaliza cobertura incompleta sem descartar texto silenciosamente

#### Scenario: No verified result is available
- **WHEN** o modelo falha ou a fonte não sustenta nenhum resultado proposto
- **THEN** o app informa que nenhum resultado foi extraído e mostra onde o processamento falhou

### Requirement: Inference runs offline and isolates clinical data

A extração SHALL funcionar no S25 em instalação limpa offline com o modelo verificado. Cada tentativa SHALL isolar o contexto e validar somente a resposta final. Texto do laudo SHALL ser tratado como dado. Qualquer conteúdo persistido SHALL permanecer cifrado. Logs, rotas e relatórios compartilháveis SHALL conter somente estados ou identificadores operacionais.

#### Scenario: Document text resembles an instruction
- **WHEN** um trecho tenta instruir a IA a alterar a extração, chamar uma ferramenta ou inventar um dado
- **THEN** o app não executa a instrução e publica somente campos que passarem pelos controles de fonte e associação

#### Scenario: Cancel before a late response
- **WHEN** a pessoa cancela durante a inferência e uma resposta chega depois
- **THEN** a execução cancelada não publica novos resultados nem repõe dados descartados

### Requirement: Product acceptance uses the authorized real report

O laudo real autorizado SHALL ser a única fonte do aceite de extração. Em instalação limpa offline no S25, SHALL haver comparação página a página com a leitura privada do PDF. O aceite exige correspondência dos resultados legíveis no escopo, com campos e vínculos conferidos. Casos ambíguos SHALL permanecer indicados para conferência.

#### Scenario: Reconcile the six-page result with the private source
- **WHEN** a extração termina no S25 com o PDF real autorizado
- **THEN** a conferência verifica cada resultado legível e seu vínculo com a página, contabiliza acertos, omissões, extras, conflitos e falhas, e rejeita o aceite se houver omissão de resultado inequívoco, campo inventado ou vínculo incorreto publicado como verificado

#### Scenario: Record test evidence outside the app
- **WHEN** o ensaio é documentado
- **THEN** somente totais, estados e configuração técnica saem do ambiente privado. O PDF, gabarito, dados pessoais e valores do laudo ficam fora do código, APKs, CI e relatórios
