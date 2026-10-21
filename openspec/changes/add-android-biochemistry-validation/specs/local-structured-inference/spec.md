# Spec Delta

## Purpose

Estruturar localmente texto de laudos com LiteRT-LM e modelo textual selecionado por ensaio, preservando evidência e avaliação determinística independente.

## ADDED Requirements

### Requirement: First version includes real on-device structured extraction
A bancada SHALL incluir inferência textual real no S25 nesta primeira versão. O modo assistido SHALL estar ativo em aparelho compatível, com modo sem IA para comparação. O candidato principal SHALL ser LFM2.5-1.2B-Thinking, com Qwen3 0.6B como alternativa se critérios técnicos ou de licença não forem atendidos. Stub ou fallback exclusivo SHALL NOT satisfazer o aceite.

#### Scenario: Process a document with the bundled model
- **WHEN** um documento suportado é processado no modo assistido
- **THEN** o modelo selecionado executa localmente e propõe campos, registrando sua identidade e participação real, sem receber imagem/PDF diretamente

### Requirement: Model is available without first-use download
A distribuição SHALL incluir pesos compatíveis e manifesto com identidade, revisão, licença, quantização, formato, tamanho e hash. A primeira execução SHALL funcionar offline. Arquivo ausente, corrompido ou incompatível SHALL ser identificado sem download automático.

#### Scenario: Install and launch without network
- **WHEN** uma instalação limpa é aberta com rede bloqueada
- **THEN** o modelo é preparado, verificado e usado sem provisionamento manual adicional

### Requirement: Model candidates are grounded in document evidence
Cada campo SHALL apontar para texto, página e evidência existentes. Validação independente SHALL rejeitar campos sem suporte, JSON inválido e schema inesperado. A saída SHALL NOT ser tratada como confiança calibrada ou confirmação humana.

#### Scenario: Model invents a reference
- **WHEN** a saída contém faixa ou unidade ausente da evidência citada
- **THEN** o candidato é rejeitado e não autoriza classificação

#### Scenario: Valid JSON cites the wrong row
- **WHEN** o JSON é válido mas a evidência não sustenta a associação
- **THEN** o sistema exige revisão em vez de aceitar apenas a estrutura bem formada

### Requirement: Inference cannot override assessment safeguards
O modelo SHALL propor campos sem diagnóstico, gravidade ou classificação final. Os dois modos SHALL usar o mesmo motor determinístico. Ambiguidades anteriores e divergências entre candidatos válidos SHALL permanecer explícitas.

#### Scenario: Model disagrees with baseline extraction
- **WHEN** parser e modelo divergem em valor, unidade, referência ou contexto
- **THEN** a divergência é preservada e a observação exige revisão sem preferência silenciosa

### Requirement: Document content is data and cannot invoke tools
O texto do laudo SHALL ser tratado como dado não confiável, sem execução de instruções, ferramentas ou acesso externo. Inferência SHALL usar apenas a evidência da execução, sem gabaritos ou histórico de outros documentos, com contexto isolado.

#### Scenario: Source contains instructions to ignore extraction rules
- **WHEN** o documento pede para inventar faixa ou alterar regras
- **THEN** nenhum comando é executado e a saída só é aceita após validação de schema e evidência

### Requirement: Bounded failures preserve baseline processing
Inferência SHALL limitar contexto, saída total, tempo e concorrência e liberar recursos. Falha de carga, schema, backend, timeout ou truncamento SHALL preservar o caminho sem IA e identificar fallback. Cancelamento SHALL impedir gravações tardias. Fallback SHALL NOT contar como inferência bem-sucedida.

#### Scenario: Model times out on one page
- **WHEN** uma página excede o orçamento
- **THEN** o sistema encerra a tentativa com segurança e preserva resultados sem IA e outras páginas

### Requirement: Model execution is reproducible and auditable
Cada tentativa SHALL registrar modo, hash/revisão/quantização, runtime, prompt/schema, backend, parâmetros, limites, duração e resultado/fallback. Prompts e respostas sensíveis SHALL permanecer em memória ou persistência criptografada, sem logs em claro.

#### Scenario: Reopen an assisted execution
- **WHEN** uma execução é reaberta após atualização do modelo
- **THEN** conserva configuração e saída anteriores sem alegar identidade entre backends distintos

### Requirement: Thinking output is bounded and separate from extracted fields
No modelo Thinking, o sistema SHALL reservar orçamento para raciocínio e resposta final e validar somente o canal final como JSON. SHALL NOT presumir que ocultar o canal thought desativa seu cálculo. Ausência de resposta final SHALL ser falha explícita; texto de raciocínio SHALL NOT ser persistido ou exibido como resultado de exame.

#### Scenario: Token budget ends during thinking
- **WHEN** o modelo esgota tokens antes da resposta final
- **THEN** a execução registra truncamento/fallback sem interpretar pensamento parcial como extração válida

### Requirement: Sessions do not leak state across documents
Cada requisição SHALL começar com estado de inferência isolado. O sistema SHALL usar nova engine quando o runtime/modelo não comprovar reset integral entre conversas, incluindo estados de convolução e KV cache.

#### Scenario: Shared prompt prefixes across documents
- **WHEN** duas requisições têm prefixos iguais e conteúdos distintos
- **THEN** a segunda não recebe estado nem conteúdo da primeira, verificado por teste de isolamento antes de habilitar reutilização
