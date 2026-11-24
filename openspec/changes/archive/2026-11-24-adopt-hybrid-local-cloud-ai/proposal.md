# Proposal

## Why

Atualmente o aplicativo embarca `2,58 GB` de pesos do `gemma-4-E2B-it.litertlm` diretamente no APK e exige execução exclusivamente local no aparelho, o que inviabiliza a distribuição leve em loja e exclui usuários com dispositivos de menor memória RAM, sem aceleração GPU compatível ou sem espaço imediato em disco. Adotar uma arquitetura híbrida **Local-First + Fallback Privado na Nuvem** usando o mesmo modelo base (**Gemma 4 E2B**) nas duas pontas mantém a privacidade e o custo zero no aparelho como caminho prioritário, ao mesmo tempo em que garante cobertura ampla com paridade de prompt/schema e baixo custo operacional (~US$ 6–24/mês na cauda longa).

## What Changes

- **BREAKING (Governança de rede e empacotamento):** Evolui o aplicativo de 100% sem permissão `INTERNET` e pesos embarcados no APK para **Local-First com download sob demanda e fallback em nuvem opt-in**:
  - Remove o empacotamento obrigatório de `gemma-4-E2B-it.litertlm` (2,58 GB) dentro do APK padrão (`app/build.gradle.kts`), migrando para download sob demanda em background com verificação estrita de tamanho e SHA-256 em `noBackupFilesDir`.
  - Habilita `INTERNET` e `ACCESS_NETWORK_STATE` exclusivamente com TLS (`usesCleartextTraffic="false"`) para dois fins isolados: (1) download verificado dos pesos locais e (2) fallback privado na nuvem mediante consentimento explícito (`opt-in`).
  - Atualiza `AGENTS.md`, avisos de privacidade da UI e auditorias de APK para refletir o modelo híbrido sem telemetria nem retenção de dados clínicos.
- **Motor de decisão e abstração unificada no cliente (`:domain`, `:inference-local`, novo `:inference-cloud`, `:app`):**
  - Introduz abstração unificada de inferência com provedores `LocalGemmaProvider` (LiteRT-LM isolado por processo/Engine) e `CloudFallbackProvider` (HTTPS para proxy privado AWS), ambos executando o mesmo protocolo `exam-vision/1` / `exam-vision-items/1`.
  - Adiciona `InferenceRoutingDecisionEngine` em `:domain` (Kotlin/JVM puro) avaliando RAM total/disponível do dispositivo (mínimo ≥ 6 GB total e folga operacional), suporte de GPU/ABI 64-bit, estado dos pesos locais (baixado/em andamento/ausente) e consentimento explícito do usuário.
  - Fluxo **opt-in explícito**: quando a inferência local não puder rodar, o app solicita autorização antes de enviar qualquer faixa para a nuvem; quem recusar permanece em modo estritamente offline (podendo aguardar/concluir o download local quando o hardware for compatível).
  - Adiciona indicador discreto do modo de execução ativo e preferência local (sem PII) em configurações/ajuda para gerenciar download dos pesos e desativar o fallback em nuvem.
- **Tarja visual local de PII antes do envio em nuvem (`:data-private-pdf`, `:domain`):**
  - Mantém paridade visual multimodal enviando sempre imagens das faixas (PNG/JPEG ≤ 512 KiB em memória), aplicando antes do envio uma etapa local de detecção de PII (CPF, e-mail, telefone, linhas/rótulos de identificação do paciente no texto posicionado do PDF digital) e pintura de tarja opaca sobre as respectivas regiões no bitmap em RAM.
- **Backend *stateless* e Infraestrutura AWS no repositório (`backend/`):**
  - Adiciona módulo `backend/` com função AWS Lambda proxy *stateless* (fora de VPC para custo fixo zero, zero persistência em S3/DB, logs estritamente técnicos sem corpo de requisição/resposta), contrato OpenAPI HTTP, autenticação do cliente + *rate limiting* por usuário e infraestrutura como código (Terraform) provisionando API Gateway HTTP/REST com TLS 1.2+, políticas IAM/SCP com fixação de região (LGPD) e invocação do **Amazon Bedrock (`google.gemma-4-e2b`)** sobre TLS + SigV4 com *Model Invocation Logging* desativado.

## Capabilities

### New Capabilities
- `hybrid-inference-routing`: Motor de decisão Local-First (RAM, aceleração GPU/ABI, estado do artefato local), fluxo de consentimento explícito (`opt-in`) para fallback em nuvem, garantia de modo estritamente offline na recusa e transparência de modo/configurações na UI.
- `on-demand-model-delivery`: Download sob demanda em background dos pesos `gemma-4-E2B-it.litertlm` fora do APK, preparação atômica com verificação de tamanho e SHA-256, acompanhamento de progresso, pausa/cancelamento, verificação de espaço livre e opção de remoção local.
- `private-cloud-inference-fallback`: Tarja local de PII em imagens de faixas antes do despacho, cliente HTTPS autenticado com timeouts e cancelamento cooperativo, e backend proxy AWS *stateless* (API Gateway + Lambda + Amazon Bedrock `google.gemma-4-e2b` sobre TLS + SigV4 em região fixa, sem retenção nem log de payload).

### Modified Capabilities
- `multimodal-vision-extraction`: Altera os requisitos de distribuição e execução dos pesos fixados para permitir APK leve com download sob demanda verificado por SHA-256 e permissão `INTERNET` estritamente controlada para download de pesos e fallback opt-in, preservando extração por faixas, schema estrito anti-alucinação e isolamento por processo/Engine no caminho local.

## Impact

- **Módulos Android afetados:** `:domain` (decisão de roteamento, detecção pura de spans de PII, tipos de consentimento/falha de rede), `:data-private-pdf` (redação/tarja visual em RAM sobre bitmaps de faixa), `:inference-local` (migração de `BundledModelStore` para `OnDemandModelStore` com download verificado + probe de hardware RAM/GPU), novo módulo `:inference-cloud` (cliente HTTPS/TLS para o proxy AWS), `:core-ui` (diálogo/cartão de consentimento opt-in, status do download de pesos e indicador discreto de modo local/nuvem), `:app` (`AndroidManifest.xml`, `app/build.gradle.kts`, `ExamViewModel`, `ExamRunner`, preferências sem PII).
- **Infraestrutura e Backend (`backend/`):** Novo diretório contendo código da AWS Lambda proxy *stateless*, especificação OpenAPI do endpoint de inferência multimodal e IaC AWS (API Gateway, rate limiting, Bedrock `google.gemma-4-e2b` em região fixa via TLS/SigV4, CloudWatch sanitizado e IAM/SCP regional).
- **Dependências e permissões:** Habilita `INTERNET` e `ACCESS_NETWORK_STATE` (mantendo `usesCleartextTraffic="false"` e `allowBackup="false"`), adiciona cliente HTTP mínimo (ex.: OkHttp ou `HttpURLConnection` com TLS estrito + WorkManager/coroutines para download em background) e mantém `:domain` 100% Kotlin/JVM puro sem SDK Android nem bibliotecas de rede.
- **Governança e limites éticos preservados:** Nenhuma alteração nas regras clínicas (`NEEDS_REVIEW`, não diagnóstico, referência bruta preservada, contexto clínico apenas em RAM, histórico cifrado com Keystore e ancoragem documental em `document-evidence-grounding`).
