# Proposal

## Why

O projeto tem um scaffolding Android e um avaliador numérico testado, mas ainda não processa laudos de ponta a ponta nem mede a qualidade da extração. Precisamos de uma bancada interna no Android para validar bioquímica básica com corpus e gabarito rastreáveis antes de ampliar o produto.

## What Changes

- Integrar importação privada de JPEG/PNG, PDF digital e PDF escaneado, extração com proveniência e processamento recuperável por página, offline desde a primeira abertura com o OCR Latin empacotado.
- Restringir o parser a português do Brasil e glicose, ureia, creatinina, sódio e potássio, em layouts explicitamente suportados. Referências vêm exclusivamente do documento; ambiguidades exigem revisão.
- Incluir LiteRT-LM como entrega P0 para estruturar texto de PDF/OCR em campos com evidência, validados antes da avaliação. O modo assistido fica ativo em aparelho compatível; o caminho sem IA permanece para comparação e fallback.
- Considerar **LFM2.5-1.2B-Thinking** como candidato principal, conforme sugestão do usuário, em substituição ao Qwen3 0.6B inicialmente proposto. Começar pelo arquivo INT4 CPU e avaliar a variante GPU separadamente no S25. Manter Qwen3 0.6B como alternativa técnica, sem empacotar dois modelos por padrão nem presumir superioridade do LFM.
- Distribuir o modelo selecionado com o aplicativo, com revisão, licença, quantização e hash fixados. Exigir execução real offline no S25 nesta versão; um stub ou fallback exclusivo não conclui a entrega de inferência.
- **BREAKING**: avaliar automaticamente campos inequívocos e completos sem confirmação humana prévia, substituindo a condição atual `Observation.confirmed` no fluxo interno. Preservar as demais barreiras conservadoras e versionar a regra; não simular confirmação humana.
- Criar corpus sintético reproduzível com gabarito anterior à geração e permitir exemplos públicos demonstrativos com procedência, condições de uso e conferência pelo usuário. A aquisição na internet ocorre fora do APK.
- Comparar execuções com gabaritos versionados, separando erros de extração, erros do avaliador e cobertura/abstenção por origem, formato e modo sem IA/assistido, incluindo falhas, tempo de raciocínio, latência e memória.
- Conectar revisão, origem visual, correções append-only, histórico e reexecução sem sobrescrever resultados originais.
- Completar a proteção de documentos e dados estruturados persistidos, sem backup e com exclusão recuperável de todos os derivados.
- Tratar corpus, inferência e medições como P0. O Samsung S25 é a referência física inicial; validação em aparelhos de 4 GB permanece pendente.

Fora do escopo: uso clínico/público, diagnóstico, urgência, faixas inventadas, conversão automática de unidades, outros analitos/idiomas, chatbot, classificação clínica por LLM, visão direta pelo modelo textual, nuvem, conta e crawler no aplicativo. Exemplos públicos não bloqueiam o corpus sintético.

## Capabilities

### New Capabilities

- `validation-corpus`: exemplos sintéticos e públicos, identidade, procedência, gabaritos versionados e revisão documental.
- `document-processing`: importação offline, extração de imagens/PDF, associação dos cinco analitos, proveniência e retomada por página.
- `local-structured-inference`: extração assistida local com modelo textual, evidência verificável, limites e fallback explícito.
- `reference-assessment`: avaliação automática conservadora e explicável, independente de confirmação prévia e gabarito.
- `validation-review`: comparação com gabarito, métricas, inspeção visual, correções e histórico imutável.
- `protected-local-history`: persistência criptografada, reabertura e exclusão coordenada de documentos e derivados.

### Modified Capabilities

Nenhuma especificação OpenSpec existente: o inventário está vazio. A alteração do avaliador existente está explicitada acima e coberta pela nova capacidade `reference-assessment`.

## Impact

Afeta `:domain`, `:data-documents`, `:data-local`, features e DI/navegação de `:app`. Promove o stub `:inference-experimental` para adaptador Android integrado, renomeado para `:inference-local`, com LiteRT-LM e modelo distribuído. Acrescenta fixtures, testes, manifesto de modelo e medição A/B; adapta documentação e CI. Bibliotecas PDF e runtime/modelo exigem verificação de licença, minSdk/ABI e memória. Não acrescenta INTERNET ou clientes de rede ao APK.

A inclusão de inferência nesta versão substitui, para esta bancada, a restrição atual de `AGENTS.md` que a mantém experimental, desabilitada e fora do MVP. Atualizar a governança na implementação, mantendo as barreiras de ambiguidade, privacidade e avaliação determinística.

Fontes dos modelos: [LFM2.5-1.2B-Thinking](https://huggingface.co/litert-community/LFM2.5-1.2B-Thinking) e [Qwen3-0.6B](https://huggingface.co/litert-community/Qwen3-0.6B). O desenho registra as revisões consultadas e os critérios para seleção, sem transformar benchmarks externos em validação do aplicativo.
