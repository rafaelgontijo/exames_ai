# Spec Delta

## Purpose

Garantir a integridade documental das observações extraídas por visão, cruzando-as com o texto posicionado do PDF quando disponível para filtrar alucinações e destacar regiões em `Ver no laudo`, e preservando fallback conservador `NEEDS_REVIEW` quando não houver texto digital ou ancoragem literal inequívoca.

## ADDED Requirements

### Requirement: Digital PDF text grounds vision proposals and filters ungrounded hallucinations

Quando a página do PDF possuir camada de texto digital posicionado, o app SHALL cruzar cada item proposto pelo modelo de visão contra o texto literal da página antes da publicação. O app SHALL rejeitar ou impedir a publicação como verificado de qualquer valor numérico/textual que não esteja ancorado na página e SHALL preservar valores, pontuação decimal, símbolos comparadores, unidades e referências brutas sem conversão ou invenção.

#### Scenario: Vision extracts a value literally present in digital PDF text
- **WHEN** a página possui texto posicionado e o resultado proposto pela visão corresponde literalmente a um trecho da página
- **THEN** o app ancora a observação ao texto da página, resolve as regiões de origem disponíveis para nome, resultado, unidade e referência e publica o item para avaliação documental conservadora

#### Scenario: Vision hallucinates a value absent from the digital PDF page
- **WHEN** a página possui texto digital legível e o modelo de visão propõe um resultado cujo valor não aparece na página
- **THEN** o app filtra o item alucinado (não o publica na lista de resultados) e sinaliza a página como exigindo conferência

#### Scenario: Vision extracts a result whose reference cannot be anchored cleanly
- **WHEN** o resultado está ancorado no texto da página, mas a referência proposta é ausente, condicional, multilinha ou não corresponde de forma inequívoca ao texto da página
- **THEN** o app preserva o resultado ancorado, não inventa faixa de referência e marca a observação como `NEEDS_REVIEW` para conferência no laudo

### Requirement: Scanned PDFs without digital text preserve visual items strictly under NEEDS_REVIEW

Quando a página do PDF não possuir camada de texto digital (PDF escaneado), o app SHALL aceitar os itens bem-formados propostos pela visão sem descartá-los por ausência de texto, mas SHALL marcar todas essas observações como `NEEDS_REVIEW` com motivo documental explícito indicando extração visual sem texto digital para conferência.

#### Scenario: Assess a numeric observation extracted from a scanned PDF page
- **WHEN** uma página escaneada (sem texto digital) produz um resultado numérico com faixa de referência aparentemente simples
- **THEN** o avaliador documental conservador ainda classifica a observação como `NEEDS_REVIEW` (nunca `WITHIN_RANGE`) e exibe nota orientando conferência visual no laudo original

#### Scenario: Open evidence view for a scanned page observation
- **WHEN** a pessoa toca em `Ver no laudo` em um resultado extraído de página escaneada sem regiões por palavra
- **THEN** o app abre a página correspondente do laudo em tela cheia (com indicação visual da faixa de origem quando disponível) e mantém visível o aviso de conferência, sem exibir caixas fabricadas por campo

### Requirement: Evidence view highlights anchored regions and preserves encrypted history compatibility

Para observações ancoradas no texto posicionado de PDFs digitais, `Ver no laudo` SHALL destacar as regiões resolvidas de resultado e referência sobre a página renderizada. A evolução do modelo de dados persistido SHALL preservar a leitura do histórico cifrado gravado por versões anteriores do aplicativo sem perda de dados nem migração destrutiva.

#### Scenario: Inspect anchored vision result in evidence view
- **WHEN** a pessoa toca em `Ver no laudo` em um item extraído por visão e ancorado ao texto posicionado de um PDF digital
- **THEN** o app exibe a página correta do PDF com destaque visual sobre as regiões encontradas para resultado e referência (quando presente)

#### Scenario: Reopen an exam saved before the multimodal upgrade
- **WHEN** o aplicativo atualizado abre o histórico local contendo extrações cifradas geradas pela versão anterior
- **THEN** o app carrega e exibe os exames anteriores normalmente sem erro de deserialização ou exclusão automática
