# Spec Delta

## Purpose

Permitir investigar erros e comparar execuções com gabaritos sem perder saídas automáticas ou ocultar abstenções e documentos incompletos.

## ADDED Requirements

### Requirement: Reviewer can inspect fields against their source
A bancada SHALL mostrar campos extraídos, estado/motivo, gabarito disponível e fonte destacada. SHALL permitir corrigir extração e gabarito em ações distintas, sem confundir revisão documental com validação clínica.

#### Scenario: Investigate an incorrect reference
- **WHEN** o usuário seleciona divergência de referência
- **THEN** vê texto bruto, faixa extraída/esperada, página e região antes de corrigir

### Requirement: Corrections and reruns preserve original executions
Cada execução SHALL preservar entrada, versões, saída automática e completude. Correções SHALL ser append-only, com antes/depois e horário. Reavaliações e reexecuções SHALL criar saídas vinculadas sem substituir a original.

#### Scenario: Correction improves classification
- **WHEN** o usuário corrige unidade e reavalia
- **THEN** a nova saída é identificada como posterior à correção e a saída original permanece consultável

### Requirement: Metrics separate extraction and evaluator correctness
A bancada SHALL medir acerto por campo/pareamento, erro ponta a ponta e erro do avaliador alimentado com gabarito em medição separada. SHALL contabilizar ausentes, extras e associações erradas, sem usar apenas linhas extraídas como denominador.

#### Scenario: Parser misses an abnormal observation
- **WHEN** uma observação fora da referência não é extraída
- **THEN** conta como erro de extração e falha ponta a ponta, sem desaparecer do denominador nem ser atribuída ao avaliador isolado

#### Scenario: Evaluator receives correct fields
- **WHEN** a medição isolada usa campos do gabarito
- **THEN** suas divergências são reportadas separadamente da avaliação sobre campos extraídos

### Requirement: Coverage and abstention remain visible
Relatórios SHALL apresentar contagens/denominadores, cobertura, NEEDS_REVIEW e NOT_CLASSIFIABLE separadamente, anormais exibidos como dentro e anormais não sinalizados. Documentos parciais SHALL aparecer incompletos e denominador vazio SHALL produzir N/A.

#### Scenario: All cases require review
- **WHEN** nada é classificado automaticamente
- **THEN** mostra cobertura zero e abstenções sem alegar acerto total

#### Scenario: An abnormal case is displayed within range
- **WHEN** esperado BELOW_RANGE/ABOVE_RANGE é exibido WITHIN_RANGE
- **THEN** destaca o erro separadamente de ausências, outros erros e abstenções

### Requirement: Reports identify data strata and execution conditions
Relatórios SHALL separar origem sintética/pública e PDF textual/escaneado/imagem, registrar versões, famílias, aparelho/Android, tempo por página e memória/falhas disponíveis. SHALL separar desenvolvimento de avaliação reservada por família.

#### Scenario: Related images are split into evaluation sets
- **WHEN** há variantes do mesmo caso
- **THEN** a família permanece na mesma partição evitando contagem como exemplos independentes reservados

#### Scenario: Initial device results
- **WHEN** uma execução ocorre no S25
- **THEN** identifica o aparelho sem declarar validação em 4 GB

### Requirement: Unreviewed and empty strata do not imply validated accuracy
O relatório SHALL excluir gabaritos pendentes dos denominadores e informar sua quantidade. SHALL distinguir resultado técnico de validação clínica e SHALL NOT mostrar estratos sem amostras como aprovados.

#### Scenario: Only synthetic ground truth is available
- **WHEN** só há gabaritos sintéticos revisados
- **THEN** limita conclusões a esses casos e indica cobertura pública indisponível

### Requirement: Baseline and assisted extraction are compared independently
A bancada SHALL comparar os modos sobre documentos/gabaritos idênticos e mesma regra, com saídas separadas. SHALL reportar erros, cobertura, divergências, carga fria, tempo até resposta final, tokens de raciocínio/saída, memória e fallback com identidade de modelo/runtime/prompt/backend.

#### Scenario: Assisted run falls back to baseline
- **WHEN** a IA falha e o parser conclui
- **THEN** distingue tentativa/falha/fallback sem contá-lo como inferência bem-sucedida

#### Scenario: Model raises automatic coverage but adds errors
- **WHEN** a cobertura e os erros fora→dentro aumentam
- **THEN** reporta ambos sem usar cobertura isolada como melhoria
