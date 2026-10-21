# Spec Delta

## Purpose

Definir exemplos rastreáveis e gabaritos independentes para medir a extração e avaliação de bioquímica básica na bancada interna Android.

## ADDED Requirements

### Requirement: Synthetic examples have independent reproducible ground truth
O corpus SHALL gerar documentos a partir de gabaritos definidos antes da renderização, com identificador, versão, hash, configuração e marcação de conteúdo sintético. O gabarito SHALL descrever campos, origem e estado esperado por observação, incluindo abstenção.

#### Scenario: Regenerate a synthetic case
- **WHEN** o mantenedor gera novamente um caso com mesma configuração, versão e semente
- **THEN** obtém os mesmos campos e gabarito, com artefatos identificáveis por hash, sem usar a saída do parser como resposta esperada

### Requirement: Corpus exercises the supported formats and failure boundaries
O corpus SHALL cobrir os cinco analitos em pt-BR, PDF digital, PDF escaneado e JPEG/PNG, com casos classificáveis e de abstenção. Variantes derivadas do mesmo exemplo SHALL compartilhar identidade de família.

#### Scenario: Generate related format variants
- **WHEN** um caso base é convertido em PDF textual, PDF escaneado e imagem
- **THEN** as variantes mantêm família e gabarito semântico, identificam formato e registram alterações de legibilidade

#### Scenario: Exercise conservative behavior
- **WHEN** o corpus inclui limites, comparadores, números ambíguos, censura, unidades incompatíveis, referência ausente, contexto desconhecido e baixa legibilidade
- **THEN** cada caso declara previamente os campos recuperáveis e classificação ou abstenção esperada, sem inventar referência clínica

### Requirement: Public examples require provenance and documentary review
Exemplos públicos SHALL registrar URL, data de obtenção, hash, condições de reutilização e estado de revisão. Apenas demonstrativos sem dados pessoais identificáveis e com reutilização documentada SHALL integrar o corpus compartilhável. A obtenção SHALL ocorrer fora do aplicativo.

#### Scenario: Public availability alone is insufficient
- **WHEN** um candidato não tem condições de uso verificadas ou contém dados pessoais identificáveis
- **THEN** ele não é incorporado ao corpus compartilhável nem aos testes versionados

#### Scenario: No public example is available
- **WHEN** nenhum exemplo público elegível foi obtido
- **THEN** o corpus sintético continua utilizável e os relatórios informam ausência de cobertura pública

### Requirement: Ground truth review is versioned separately from extraction
O sistema SHALL permitir conferir e corrigir o gabarito contra a fonte, registrando versões e autoria local. Exemplos sem revisão SHALL permanecer exploratórios e fora dos denominadores de acerto. Contexto clínico não resolvido SHALL permanecer pendente.

#### Scenario: Reviewer changes an expected value
- **WHEN** o usuário corrige o gabarito após uma execução
- **THEN** o gabarito recebe nova versão e comparações anteriores continuam vinculadas à versão usada, sem alterar a extração original

#### Scenario: Public example has no reviewed ground truth
- **WHEN** a extração roda em exemplo público ainda não conferido
- **THEN** a saída pode ser inspecionada, mas o exemplo não contribui para métricas de acerto
