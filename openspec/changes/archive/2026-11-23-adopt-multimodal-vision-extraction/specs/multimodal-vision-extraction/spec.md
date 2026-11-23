# Spec Delta

## Purpose

Extrair resultados laboratoriais de páginas de PDFs (digitais de qualquer layout ou escaneados) por meio de inferência visual multimodal local em faixas horizontais, executada 100% offline no aparelho com isolamento por solicitação e validação estrita da saída.

## ADDED Requirements

### Requirement: PDF pages are extracted through banded local multimodal vision

O app SHALL processar todas as páginas de um PDF importado usando o pipeline visual multimodal em faixas horizontais sobrepostas (até 3 faixas por página), independentemente de o layout corresponder a cabeçalhos tabulares conhecidos. O app SHALL manter o ponto de entrada restrito à seleção de arquivos PDF (digitais ou escaneados), sem abrir entrada de fotos/galeria nesta mudança.

#### Scenario: Extract an unfamiliar multi-block digital PDF layout
- **WHEN** a pessoa seleciona um PDF digital cujo layout não segue cabeçalhos tabulares fixos
- **THEN** o app renderiza cada página em memória, fatia a página em faixas horizontais sobrepostas, executa inferência visual local por faixa e apresenta os resultados extraídos agrupados por página em ordem documental

#### Scenario: Extract a scanned PDF without embedded text
- **WHEN** a pessoa seleciona um PDF composto apenas por imagens escaneadas (sem camada de texto digital)
- **THEN** o app aceita o PDF, executa a extração visual em faixas normalmente e apresenta os resultados propostos com sinalização conservadora de conferência obrigatória

#### Scenario: Skip empty or blank bands before inference
- **WHEN** uma faixa horizontal de uma página não contém conteúdo visual legível (por exemplo, área em branco ou margem vazia)
- **THEN** o app não invoca o modelo de IA para aquela faixa, economizando tempo de carga/geração e evitando propostas espúrias em faixas vazias

### Requirement: Vision prompt and response parsing enforce strict anti-hallucination contracts

O protocolo visual SHALL utilizar um prompt de schema neutro (sem valores ou nomes clínicos concretos no exemplo de formato), validar exclusivamente o canal final em JSON estrito sem markdown e deduplicar de forma conservadora itens repetidos na sobreposição entre faixas adjacentes.

#### Scenario: Model returns malformed JSON or markdown-fenced output for one band
- **WHEN** a resposta final de uma faixa não é um objeto JSON válido conforme o schema esperado ou excede o limite de caracteres
- **THEN** o app descarta a resposta inválida daquela faixa, registra falha operacional explícita na página e preserva resultados válidos já extraídos das demais faixas e páginas

#### Scenario: Overlapping bands report the same measurement
- **WHEN** duas faixas adjacentes extraem o mesmo exame na zona de sobreposição vertical
- **THEN** o app consolida o item sem duplicá-lo na lista final da página, preservando a versão com maior completude verificável e sinalizando conflito se houver divergência incompatível de valor

### Requirement: Pinned multimodal weights run offline in isolated per-request processes

A distribuição padrão SHALL empacotar pesos multimodais fixados por revisão, tamanho e SHA-256 sob licença Apache-2.0 (`gemma-4-E2B-it.litertlm`), sem adicionar permissão `INTERNET` nem bibliotecas de rede. Cada faixa SHALL executar em `Engine` e processo filho `:inference` novos, com payload de imagem transferido exclusivamente em memória dentro de limite estrito de bytes, respeitando prazos de carga/geração e cancelamento cooperativo.

#### Scenario: Transfer band image to isolated inference process without disk plaintext
- **WHEN** o app envia uma faixa renderizada para o processo `:inference`
- **THEN** os bytes da imagem são limitados em tamanho e trafegam apenas em memória, sem gravação de arquivos temporários de imagem clínica no armazenamento e sem registro de conteúdo clínico em logs

#### Scenario: Cancel extraction during banded vision inference
- **WHEN** a pessoa cancela o processamento enquanto uma faixa está carregando ou gerando no processo `:inference`
- **THEN** o processo filho é encerrado dentro do prazo de limpeza, nenhuma resposta tardia é publicada e o estado ativo é limpo sem corromper o histórico existente

#### Scenario: Device lacks space or multimodal asset fails integrity check
- **WHEN** o artefato multimodal empacotado está ausente, corrompido ou não há espaço livre suficiente para preparação atômica no diretório privado
- **THEN** o app falha explicitamente com código operacional seguro e mensagem informativa ao usuário, sem degradar silenciosamente para resultados fabricados
