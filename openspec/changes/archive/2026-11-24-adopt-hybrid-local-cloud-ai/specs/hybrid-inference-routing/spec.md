# hybrid-inference-routing Specification

## Purpose

Definir o roteamento Local-First entre inferência on-device e fallback privado na nuvem no cliente, combinando critérios determinísticos de capacidade do dispositivo, estado dos pesos locais e consentimento explícito (`opt-in`) do usuário com total transparência de modo de execução.

## ADDED Requirements

### Requirement: Decision engine prioritizes local inference before cloud fallback

Antes de iniciar uma extração, o sistema SHALL avaliar de forma determinística a elegibilidade para execução local considerando: (1) memória RAM total e disponível compatível com o modelo local (mínimo de 6 GB de RAM total e folga operacional mínima para evitar encerramento por falta de memória), (2) suporte a ABI 64-bit e aceleração GPU/NPU compatível no aparelho, e (3) presença e integridade verificada do artefato de pesos locais. Sempre que todos os critérios locais forem atendidos, o sistema SHALL executar a extração exclusivamente no aparelho, sem abrir conexão de rede.

#### Scenario: Capable device with verified local weights runs strictly on-device
- **WHEN** o aparelho possui RAM compatível, aceleração GPU/ABI 64-bit e os pesos locais do Gemma 4 E2B já estão baixados e íntegros
- **THEN** o motor de decisão seleciona execução local e executa a extração integralmente no aparelho sem realizar chamadas de rede

#### Scenario: Low-memory or unsupported-GPU device routes to cloud eligibility
- **WHEN** o aparelho possui RAM total inferior ao mínimo exigido ou não oferece aceleração GPU/ABI 64-bit suportada
- **THEN** o motor de decisão classifica o aparelho como inelegível para inferência local e apresenta o caminho de fallback privado em nuvem sujeito a consentimento explícito

#### Scenario: Capable device while local weights are still downloading
- **WHEN** o aparelho atende aos requisitos de hardware, mas o download dos pesos locais ainda não foi concluído ou está ausente
- **THEN** o motor de decisão informa que o modelo local ainda não está pronto e permite ao usuário escolher entre aguardar a conclusão do download local ou autorizar o fallback privado na nuvem nesta leitura

### Requirement: Cloud fallback requires explicit opt-in consent and preserves offline-only mode on refusal

O sistema SHALL NOT enviar imagens ou trechos de laudos para a nuvem sem consentimento explícito prévio (`opt-in`) do usuário. O aviso de consentimento SHALL informar claramente que faixas do laudo (com mascaramento local de PII quando houver texto digital) serão enviadas de forma criptografada a um serviço privado sem retenção nem uso para treinamento. Caso o usuário recuse ou desative o fallback em nuvem, o sistema SHALL permanecer em modo estritamente offline sem tráfego de inferência.

#### Scenario: User declines cloud fallback consent
- **WHEN** o aparelho não pode executar inferência local no momento e o usuário recusa o uso do fallback em nuvem
- **THEN** nenhuma requisição de inferência é enviada pela rede, a leitura atual é interrompida de forma segura com aviso claro e a preferência de permanecer offline é respeitada

#### Scenario: User explicitly authorizes cloud fallback
- **WHEN** o aparelho não pode executar inferência local no momento e o usuário autoriza explicitamente o fallback privado na nuvem
- **THEN** o sistema registra apenas a preferência não identificável de consentimento em armazenamento de preferências sem PII e executa a extração via provedor de fallback na nuvem com o mesmo contrato de schema da execução local

#### Scenario: User revokes cloud fallback in settings
- **WHEN** o usuário desativa o fallback em nuvem nas configurações/ajuda do aplicativo
- **THEN** leituras subsequentes não utilizam a nuvem mesmo quando o modelo local estiver indisponível, exigindo nova autorização explícita caso o usuário deseje reabilitar o fallback

### Requirement: Execution mode is transparently indicated and clinical guardrails remain identical across providers

O sistema SHALL exibir um indicador discreto e acessível do modo de execução ativo (local no aparelho ou nuvem privada sem retenção) durante o processamento e na área de configurações/ajuda, sem introduzir linguagem diagnóstica. Independentemente do provedor utilizado (local ou nuvem), o sistema SHALL aplicar integralmente a mesma validação de schema, ancoragem contra o texto digital do PDF, preservação de referências brutas e avaliação conservadora (`NEEDS_REVIEW`).

#### Scenario: Processing banner reflects active execution provider
- **WHEN** uma extração ocorre por meio do provedor de fallback privado na nuvem após consentimento
- **THEN** a tela de processamento e a visualização de configurações indicam de forma discreta que a execução utiliza fallback privado criptografado sem retenção, em vez de afirmar execução 100% offline

#### Scenario: Cloud response undergoes identical post-processing and grounding
- **WHEN** o provedor em nuvem retorna uma resposta JSON para uma faixa do PDF
- **THEN** o cliente valida rigorosamente o canal final contra o schema `exam-vision-items/1`, filtra alucinações contra o texto posicionado do PDF digital e aplica exatamente o mesmo avaliador conservador utilizado na inferência local
