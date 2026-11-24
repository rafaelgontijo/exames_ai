# private-cloud-inference-fallback Specification

## Purpose

Fornecer um caminho de inferência multimodal em nuvem com paridade de modelo (`google.gemma-4-e2b`), privacidade reforçada por tarja local de PII antes do envio e infraestrutura AWS estritamente stateless, sem persistência, sem log de payloads e com tráfego criptografado em região fixa.

## ADDED Requirements

### Requirement: Client applies local visual PII redaction before dispatching band images to cloud

No caminho de fallback em nuvem, o cliente SHALL enviar a imagem de cada faixa dentro do mesmo limite de bytes da inferência local (PNG/JPEG ≤ 512 KiB em memória), mantendo paridade visual multimodal. Antes de codificar e transmitir qualquer imagem de página com camada de texto digital, o cliente SHALL executar detecção local de PII explícito (CPF, e-mail, telefone e campos/linhas de cabeçalho de identificação do paciente) e pintar tarjas opacas em memória sobre as regiões correspondentes do bitmap da faixa, sem gravar imagens em disco.

#### Scenario: Digital PDF band contains patient header or explicit PII
- **WHEN** uma faixa de um PDF digital contém texto posicionado correspondente a CPF, e-mail, telefone ou linha de identificação do paciente e o usuário autorizou o fallback em nuvem
- **THEN** o cliente calcula as caixas de redação localmente, aplica tarjas opacas diretamente sobre o bitmap da faixa em memória antes da codificação e envia apenas a imagem tarjada pela rede, preservando os valores laboratoriais e faixas de referência visíveis

#### Scenario: Scanned PDF page without digital text routed to cloud fallback
- **WHEN** uma página escaneada sem camada de texto digital é processada pelo fallback em nuvem após consentimento explícito
- **THEN** o aviso de consentimento informa que páginas sem texto digital não permitem tarja automática baseada em texto, a imagem da faixa trafega apenas em memória sobre TLS e as observações extraídas permanecem estritamente marcadas como `NEEDS_REVIEW` (`VISUAL_EXTRACTION_WITHOUT_DIGITAL_TEXT`)

### Requirement: Stateless AWS cloud proxy invokes Bedrock Gemma 4 E2B with zero retention and regional pinning

O backend de fallback em nuvem SHALL ser composto por API Gateway (TLS 1.2+) + função proxy *stateless* (AWS Lambda) que invoca o endpoint regional do Amazon Bedrock (`google.gemma-4-e2b`) sobre TLS + assinatura SigV4 exclusivamente em região fixa compatível com a política de privacidade/LGPD. O backend SHALL exigir autenticação do cliente e aplicar *rate limiting* por usuário/cliente, SHALL NOT persistir payloads de requisição ou resposta em banco de dados ou storage, SHALL manter o *Model Invocation Logging* do Amazon Bedrock desativado e SHALL registrar em logs apenas métricas técnicas sanitizadas (latência, status HTTP, contagem de tokens e código operacional de erro).

#### Scenario: Authenticated multimodal band request succeeds through private Bedrock proxy
- **WHEN** o cliente envia uma requisição HTTPS autenticada contendo prompt `exam-vision/1` e a imagem de faixa em memória dentro dos limites permitidos
- **THEN** a Lambda proxy valida limites e autenticação, invoca `google.gemma-4-e2b` no Amazon Bedrock sobre TLS + SigV4 na região fixa com parâmetros determinísticos, retorna apenas o canal final estruturado no contrato HTTP e descarta imediatamente o payload da memória sem gravação em storage nem logs de conteúdo

#### Scenario: Unauthenticated or rate-limited client request is rejected
- **WHEN** uma requisição chega ao API Gateway sem credencial válida ou excede o limite de taxa configurado para o usuário
- **THEN** o backend rejeita a chamada com status HTTP `401`/`403` ou `429` sem invocar o Amazon Bedrock e o cliente traduz a falha para código operacional seguro sem expor dados clínicos

#### Scenario: Cloud request fails due to timeout, connectivity loss, or cancellation
- **WHEN** o usuário cancela a extração ou ocorre falha de rede/timeout durante uma chamada ao fallback em nuvem
- **THEN** o cliente aborta a requisição HTTP em andamento imediatamente, não publica respostas tardias, não deixa dados clínicos em cache HTTP de disco e registra falha operacional explícita na página afetada preservando resultados válidos das demais faixas e páginas
