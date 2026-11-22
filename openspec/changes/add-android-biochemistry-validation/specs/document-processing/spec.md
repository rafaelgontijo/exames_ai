# Spec Delta

## Purpose

Transformar documentos locais em observações rastreáveis dos cinco analitos suportados, preservando evidências e falhas sem depender de rede.

## ADDED Requirements

### Requirement: Processing works offline from first launch
O aplicativo SHALL importar JPEG/PNG, PDF digital e PDF escaneado pelo seletor do sistema e executar extração e avaliação offline desde a primeira abertura, sem download inicial de modelos ou permissão de rede.

#### Scenario: Fresh offline installation
- **WHEN** o usuário abre instalação limpa em modo avião e importa cada formato
- **THEN** o aplicativo processa os documentos sem conexão, conta ou download

### Requirement: Import creates an independent validated private copy
O sistema SHALL validar formato e limites reais de leitura, criar cópia privada criptografada e calcular hash antes de agendar processamento. O trabalho SHALL funcionar sem depender da URI externa e SHALL comunicar arquivos inválidos ou acima dos limites.

#### Scenario: Source access is revoked
- **WHEN** a importação termina e o provedor externo deixa de disponibilizar o arquivo
- **THEN** processamento, reabertura e fonte continuam disponíveis pela cópia privada

#### Scenario: Provider size is inaccurate
- **WHEN** o stream excede o limite apesar dos metadados informados
- **THEN** a importação para com erro recuperável e remove artefatos parciais

### Requirement: Extraction preserves field-level provenance
Cada campo SHALL manter texto bruto, página e região ou trecho de origem, versão e método de extração. A prévia SHALL destacar a fonte correta e confiança desconhecida SHALL permanecer desconhecida. PDF digital SHALL usar texto posicionado quando utilizável e OCR como fallback por página.

#### Scenario: Inspect a reference on a rotated page
- **WHEN** o revisor seleciona referência em página rotacionada ou redimensionada
- **THEN** a prévia abre a página e destaca a região correspondente usando a transformação registrada

#### Scenario: PDF contains mixed page types
- **WHEN** um PDF contém páginas textuais e escaneadas
- **THEN** cada página registra o método usado e preserva evidência consultável

### Requirement: Parser has an explicit supported scope
O parser SHALL reconhecer glicose, ureia, creatinina, sódio e potássio em pt-BR nos layouts suportados. SHALL associar resultado, unidade e referência da mesma observação, preservar candidatos concorrentes e sinalizar conteúdo fora do escopo sem descarte silencioso.

#### Scenario: Adjacent columns have competing references
- **WHEN** duas faixas podem corresponder ao resultado sem associação inequívoca
- **THEN** o sistema preserva a evidência e solicita revisão em vez de escolher pela proximidade isolada

#### Scenario: Unsupported analyte or layout
- **WHEN** há outro analito ou layout não suportado
- **THEN** o sistema informa a limitação sem apresentar campos não extraídos como dentro da referência

### Requirement: Page processing is recoverable and bounded
O processamento SHALL registrar progresso por página, preservar páginas válidas após falha parcial e permitir cancelamento e retomada idempotente. Execuções incompletas SHALL ser identificadas. Limites de bytes, páginas e imagem SHALL ser explícitos.

#### Scenario: Process dies after a page completes
- **WHEN** o processo morre após concluir uma página e é reiniciado
- **THEN** retoma sem duplicar observações ou perder páginas concluídas, sem prometer execução durante force-stop

#### Scenario: One page is corrupt
- **WHEN** uma página falha e outras são processadas
- **THEN** o sistema mostra falha e execução parcial, conserva resultados válidos e permite retentar a página

### Requirement: Assisted extraction retains the document-processing contract
O modo assistido SHALL fornecer texto/evidência local ao modelo, validar candidatos e preservar proveniência, checkpoints e limites por configuração. SHALL NOT enviar imagem/PDF diretamente ao modelo textual nem apresentar saída de outra configuração como inferência nova.

#### Scenario: Resume after changing extraction mode
- **WHEN** muda o modo ou a revisão do modelo
- **THEN** uma execução identificada pela nova configuração preserva a anterior sem misturar checkpoints
