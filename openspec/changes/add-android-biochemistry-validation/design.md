# Design

## Context

Ver `proposal.md` para motivação e `specs/*/spec.md` para os contratos. A base observada é `59ee91a`:

- `ImportViewModel` apenas inspeciona URI; resultados e histórico ainda são estados vazios.
- `LocalTextRecognizer` já inclui OCR Latin; confiança desconhecida é `null`.
- `PdfPreviewRenderer` renderiza URI externa, sem extrair texto embutido ou resolver reabertura criptografada.
- O avaliador `REF_NUMERIC/1.0.0` exige confirmação e tem testes numéricos/conservadores existentes.
- Room v1 guarda campos sensíveis em claro; avaliação keyed por observação é sobrescrita, sem histórico de execuções.
- Arquivos já usam AES-GCM/Keystore. Exclusão tem outbox e worker, sem integração UI; `DeletionWorker` abre sua própria base.
- `:inference-experimental` é stub desconectado de `:app`. O usuário autorizou inferência P0, substituindo a exclusão anterior do MVP, e sugeriu LFM2.5-1.2B-Thinking no lugar do Qwen.
- O minSdk original era 23; o usuário aprovou elevar para 24 após inspeção dos AARs LiteRT-LM. SDK de compilação permanece 36. Não houve teste físico no S25 nesta conversa.

Esta proposta foi reconstruída após substituição do ambiente que continha o commit local `754820d`, antes do push. Preserva o escopo acordado e incorpora a avaliação de LFM solicitada durante a recuperação; não é uma recuperação byte a byte do objeto Git antigo.

## Goals / Non-Goals

**Goals:**

- Integrar corpus, importação, extração, avaliação, comparação, revisão, reabertura e exclusão no Android.
- Manter domínio JVM puro, configuração rastreável, sessões isoladas e nenhuma dependência do gabarito no processamento original.
- Incluir inferência real nesta versão e comparar seu efeito com o caminho sem IA.
- Usar primitivas de plataforma para criptografia e adaptadores substituíveis de PDF/modelo.

**Non-Goals:**

- Não criar backend, telemetria, crawler Android, treino de modelo ou interpretação universal de layouts.
- Não substituir OCR por visão do modelo textual nem delegar a classificação clínica ao LLM.
- Não inferir validação clínica de testes sintéticos ou desempenho no S25 de benchmarks de outro aparelho.

## Decisions

### 1. Corpus declarativo e gabarito independente

Adicionar `validation/corpus/` e gerador em `tools/corpus/`, executável no computador; a extração avaliada continua no Android. Manifesto contém caseId, familyId, origem, formato, hash, layout/versão, partição, transformações e gabarito. Gabaritos incluem campos brutos/canônicos, estados/motivos e evidências definidos antes de renderizar; não calcular respostas esperadas pelo próprio parser/avaliador sob teste.

Começar com dois layouts: tabela simples e tabela com faixas condicionais/rodapé. Cobrir cinco analitos, limites, comparadores, números ambíguos, censura, unidades diferentes, campos ausentes, faixas concorrentes e degradação/rotação. Gerar PDF textual, PDF rasterizado e JPEG/PNG. As faixas são dados sintéticos de teste, nunca tabela clínica do produto. Fixar seeds, ferramentas e fontes; verificar semântica e hashes de artefatos para detectar deriva.

Famílias inteiras permanecem na mesma partição de desenvolvimento ou avaliação reservada. Pacotes locais de corpus/gabarito têm schema, hashes e limites; rejeitar IDs/caminhos inválidos. Exemplos públicos são opcionais e adquiridos fora do APK, com URL/data/condições de uso/hash e revisão pelo usuário. Sem revisão ficam exploratórios e fora dos denominadores. Não exigir obtenção pública para iniciar a validação sintética.

Alternativas rejeitadas: usar saída do modelo como gabarito contaminaria a avaliação; crawler amplo bloquearia a primeira evidência reproduzível.

### 2. Execuções imutáveis e correções separadas

Adicionar contratos puros `CorpusCase`, `GroundTruthVersion`, `ProcessingRun`, `PageCheckpoint`, `ExtractedFieldEvidence` e `Comparison`. Cada execução registra documento/hash, versões de extrator/parser/regra, modo, estado, dispositivo e configuração de inferência. Observações e avaliações passam a ser locais à execução.

Preservar extração original, cadeia append-only de correções e projeção efetiva para reavaliação. Revisão de gabarito e correção da extração são ações distintas. Uma mudança de gabarito cria nova comparação vinculada à versão; não altera relatórios anteriores. Reexecução/correção recebe nova identidade, sem inflar retroativamente métricas. Usar IDs opacos em navegação, logs técnicos e agendamento.

### 3. Importação e processamento recuperável

Implementar repositórios em `:data-local`, conectados por Hilt. Copiar SAF para store criptografado, validar assinatura/formato e limite real, calcular hash e só então agendar. Não depender de grant futuro da URI. Reconciliar cópias órfãs e importações interrompidas.

WorkManager recebe somente IDs, sem texto/URI em WorkData nem constraint de rede. Etapas por página: leitura → texto posicionado/OCR → parser e candidatos assistidos → validação de evidência → avaliação determinística → checkpoint. Salvar saída e checkpoint na mesma transação; chave idempotente `(runId,page,mode)`. Não misturar configurações. Páginas falhas produzem estado parcial, não descarte das válidas. Registrar cancelamento e retomada sem prometer execução durante force-stop.

Exclusão marca tombstone antes de cancelar workers. Toda gravação verifica elegibilidade documental na mesma transação; impedir callbacks tardios e criação de arquivos após limpeza. Outbox só é confirmada quando escritores não puderem ressuscitar dados. Reconciliar trabalhos, arquivos e tombstones após reinício.

Limites técnicos iniciais: 20 MiB já existentes por documento, 20 páginas, uma página ativa e raster de maior lado até 2048 pixels; são parâmetros ajustáveis por medição, não critérios clínicos. Limitar imagem antes da alocação. Limites de documentos não se aplicam aos pesos do modelo.

### 4. PDF, OCR e coordenadas

Adaptador PDF recebe bytes autenticados limitados do store e fornece texto posicionado/raster sem temporário persistente em claro. Avaliar PDFBox-Android para texto e PDFium Android com abertura por byte array para raster, verificando licença, manutenção, API 24 e ABI/16 KiB antes de fixar versões. São candidatas, não dependências já comprovadas.

O spike deve abrir texto/raster sem URI original e sem gravar PDF descriptografado em disco. Substituir biblioteca se necessário, mantendo o contrato; não relaxar criptografia. O `PdfRenderer` atual só pode ser reutilizado com descritor seguro compatível com esse contrato.

Decidir por página em PDF híbrido: texto posicionado quando utilizável, OCR do raster em fallback. Existência de texto não garante associação confiável. Registrar dimensões, escala, rotação e transformação, incluindo EXIF de imagens. Cada campo aponta para span/região consultável; mapear para o mesmo sistema de coordenadas da prévia. Não inventar confiança de extração.

### 5. Parser e motor determinístico

Reconhecer somente cinco analitos e abreviações testadas em layouts identificados/versionados. Preservar texto bruto e candidatos; não usar proximidade isolada para selecionar referência concorrente. Aplicabilidade só pode ser resolvida com contexto explícito e inequívoco; não presumir adulto, sexo ou jejum.

Introduzir `REF_NUMERIC/2.0.0`: remover exclusivamente a confirmação prévia, sem marcar confirmed=true artificialmente. Preservar BigDecimal, números ambíguos, censura, unidade explícita igual, associação, contexto e limites válidos. Ausência inequívoca de referência e qualitativos ficam NOT_CLASSIFIABLE; ambiguidade anterior tem precedência e fica NEEDS_REVIEW. Confirmação genérica não elimina bloqueios.

Atualizar o teste de não confirmado para classificação automática inequívoca e estado de revisão intacto; conservar e ampliar testes de todos os bloqueios e limites. Execuções legadas permanecem na regra 1.0.0. LLM apenas propõe campos: usa os mesmos validadores e o mesmo avaliador, nunca conhecimento de referência externo.

### 6. Comparações e métricas

Comparação pura no domínio, pareamento um-para-um por caso/página/analito/evidência, sem casar pela resposta esperada. Duplicatas/associações não resolvidas são divergências. Relatar texto bruto e normalização separadamente.

- Acerto por campo: corretos / esperados revisados, contando ausentes como incorretos e extras separadamente.
- Cobertura: observações esperadas com estado numérico automático / esperadas revisadas. Extras não aumentam o numerador.
- Separar NEEDS_REVIEW, NOT_CLASSIFIABLE, ausentes, parciais e falhas.
- Destacar esperado BELOW/ABOVE exibido WITHIN, com contagem e denominador de anormais esperados.
- Reportar anormais não sinalizados, discriminando abstenção, ausência, WITHIN e outros erros, sem chamar somente uma categoria de todo falso negativo.
- Avaliador isolado recebe campos do gabarito em ensaio separado; o pipeline original nunca recebe gabarito.

Estratificar por origem, formato, analito, layout, partição e modo. Reportar denominadores e N/A para vazio. Comparar modos com mesmos documentos/gabaritos/regra; distinguir tentativa de IA, participação real e fallback. Medir carregamento frio, inferência quente, tempo até resposta final, tokens de raciocínio/saída, erros de schema/evidência, variabilidade, memória e falhas. Não confundir primeiro token de pensamento com primeira resposta útil. Amostras de memória não são pico garantido; identificar método. Latências usam relógio monotônico; tudo local e sem telemetria.

### 7. Persistência protegida

Codec separado com AES-GCM/Keystore, IV novo por gravação e AAD por entidade/ID/versão. Room armazena em claro apenas IDs opacos, relações e metadados operacionais necessários. Texto, analitos, valores, faixas, estados/motivos clínicos, gabaritos, correções e relatórios ficam em payloads autenticados cifrados.

Redesenhar entidades/mapeamentos sem duplicar campos sensíveis em claro. Repositórios decodificam em memória; pesquisa sensível fica limitada à aplicação, sem índice SQL em claro. Outbox opera com IDs opacos sem precisar descriptografar. Arquivos, prévias, OCR e eventuais traces persistidos seguem o store criptografado. Não persistir raciocínio intermediário; desabilitar caches sensíveis não protegidos. Pesos estáticos não contêm dados clínicos e podem ser privados sem criptografia clínica.

SQLCipher foi alternativa considerada; payloads evitam outra dependência nativa e atendem à bancada sem busca clínica SQL. O contêiner SQLite/metadados operacionais não é integralmente cifrado; documentar esse limite. Falha de chave produz indisponibilidade explícita, sem fallback em claro nem recriação silenciosa.

### 8. UI nos módulos existentes

Importação recebe documentos/pacotes e progresso. Resultados mostram campos, estado/motivo, gabarito, diferenças e fonte visual, com ações distintas de correção e revisão de gabarito. Histórico reabre documentos/execuções e aciona exclusão completa. Consumir casos de uso/StateFlow e DI, sem APIs Android no domínio.

Mostrar modo sem IA/assistido, preparação do modelo, uso efetivo e fallback. Navegação leva IDs opacos. Estados em pt-BR distinguem parcial, pendente, não classificável, automático e revisado; não depender só de cor. Validar TalkBack, contraste e fonte ampliada. A UI deixa de exigir confirmação antes de avaliar.

### 9. Seleção de modelo LiteRT-LM

Em 21/10/2026 foi possível consultar os dois repositórios no ambiente atualizado. Foram lidos API de metadados, cards, manifesto e licença do LFM; os binários não foram baixados nem executados. Hashes abaixo são metadados publicados, ainda precisam ser conferidos no arquivo efetivamente empacotado.

| Item | Candidato principal | Alternativa |
| --- | --- | --- |
| Repositório | `litert-community/LFM2.5-1.2B-Thinking` | `litert-community/Qwen3-0.6B` |
| Revisão consultada | `f8b5b3a3eea2e3859e821ad9d9222c64d649eff7` | `e6687177e00897731e7716267eebd4eba0dde7c2` |
| Arquivo inicial | `LFM2.5-1.2B-Thinking_int4.litertlm` | `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm` |
| Tamanho publicado | 736015744 bytes | cerca de 329 MB no card |
| Contexto publicado | 4096 | 4096 para esse arquivo |
| Licença publicada | LFM Open License v1.0 | Apache-2.0 |

LFM CPU INT4: SHA-256 publicado `da3c0ad154b78f33537b117fbe747cc1d4fd3507479002b159998f4023f32a84`. A variante `_int4_gpu.litertlm` tem 736220768 bytes e SHA-256 `51130e6e57a940bbfbb66f67698c47fd10c7051a70e7247fc8b084ccce4d54ae`; testar separadamente e nunca selecionar GPU para o arquivo CPU supondo compatibilidade. INT8 é opção de ensaio se necessário, não segundo peso empacotado por padrão.

**Decisão:** começar o ensaio com LFM CPU INT4, manter Qwen como alternativa e selecionar o único artefato da distribuição após testes de JSON, evidência, pt-BR, memória, latência, isolamento e adequação da licença. A proposta aceita a consideração do LFM, sem declarar que já superou Qwen nem exigir baixar dois modelos no app. Se houver inviabilidade, registrar evidência da seleção da alternativa; não substituir por stub.

**Licença:** a LFM Open License v1.0 exige retenção de licença/avisos e contém limitação de uso comercial associada ao limiar de US$ 10 milhões de receita anual. Confirmar adequação do uso concreto antes da distribuição; não rotular LFM como Apache-2.0 nem presumir uso comercial irrestrito. Planejamento e comparação técnica não estabelecem elegibilidade jurídica do distribuidor.

**Runtime:** fixar versão estável do AAR `com.google.ai.edge.litertlm:litertlm-android`, sem `latest.release`. O card LFM exige pelo menos 0.15 para o tipo lfm2, e pelo menos 0.16 para GPU; contém relatos sobre 0.18 que devem ser verificados na versão escolhida. Verificar minSdk, arm64 e páginas de memória de 16 KiB sem elevar API mínima silenciosamente ou supor NPU do S25. Versões mínimas publicadas não substituem teste integrado.

**Thinking:** o card informa que não pode ser desligado, inclusive com enable_thinking=false em 0.18. Não reutilizar a configuração não-thinking planejada para Qwen. Separar thought do canal final; somente resposta final pode alimentar JSON. Não exibir/persistir pensamento intermediário. Reservar inicialmente até 2048 tokens de geração total dentro do contexto 4096, incluindo raciocínio; considerar orçamento de thought de 300 se suportado e efetivo. Dimensionar entrada/template para caber; não somar 4096 de entrada a 2048 de saída. Truncamento antes de JSON final é falha, não sucesso parcial.

**Estado:** manifesto/card relatam problemas de estado ShortConv ao criar novas conversas na mesma engine, além de problemas multi-turn. Por padrão criar nova engine por requisição, sem reaproveitar KV/estado entre documentos ou trechos até teste comprovar reset. Medir custo de reinicialização. Os relatos externos são condições a testar, não bugs do aplicativo já reproduzidos.

### 10. Contrato e lifecycle da inferência

Promover/renomear o módulo para `:inference-local`, interface pura no domínio e DI no Android. Entrada é texto posicionado com IDs de spans; saída JSON versionada contém apenas analito, valor bruto, unidade, faixa, condições e evidências. Ausências ficam null. Não enviar pixels nem fornecer gabarito, correções posteriores ou outra sessão.

Validar tipos, schema, lista de analitos e IDs, suporte lexical e associação espacial/contextual. JSON válido com linha errada continua inválido como evidência. Conflito parser/modelo, ambiguidade anterior ou contexto desconhecido exige revisão; modelo não completa faixa por conhecimento prévio. Sem tools e sem execução de instruções do laudo.

Inicializar fora da UI, uma requisição ativa, engine/sessão descartáveis. Usar decodificação greedy quando suportada, registrar configuração real, sem prometer determinismo bit a bit. Segmentar por linhas/tabelas preservando evidência se entrada exceder orçamento; não truncar silenciosamente. Diferenciar orçamento de carga fria do de geração, provisoriamente 120 s cada, ajustável após medição no S25.

Timeout de coroutine não garante cancelamento JNI: provar interrupção; se necessário hospedar engine em serviço local de processo separado e encerrar esse processo. Descartar callbacks tardios após cancelamento/exclusão. Capturar erro de carga, backend, schema, contexto, truncamento e OOM recuperável, sem perder páginas. Registrar motivo de fallback ao parser, sem contar como sucesso de IA.

**Distribuição:** empacotar um artefato validado em asset sem compressão na distribuição interna e copiar atomicamente para noBackupFilesDir/models com verificação de tamanho/hash e espaço. Staging reproduzível no build; não adicionar centenas de MB ao Git comum. Considerar espaço de APK e cópia. A primeira instalação é autossuficiente offline, sem importação manual do modelo posterior. Pesos compartilhados permanecem após excluir documento; sessões/derivados não.

Aceite P0 exige inferência real na instalação limpa offline do S25, com modelo selecionado, versões e métricas A/B. Fallback exclusivo não satisfaz a entrega. Benchmarks de raciocínio matemático, outros aparelhos e backends não demonstram acerto de extração de laudos em pt-BR.

## Risks / Trade-offs

- Corpus sintético superestima desempenho → separar origem/partição e não declarar validação clínica.
- LFM aumenta tamanho/tempo de raciocínio e tem licença diferente → ensaio no S25, documentação dos termos, limite de recursos e alternativa Qwen.
- Thought consome orçamento sem JSON → orçamento explícito, canal final separado e falha de truncamento visível.
- Vazamento de estado entre sessões → engine nova por requisição até comprovar reset; teste com prefixos iguais e documentos distintos.
- Alucinação/instruções no laudo → schema, evidência, associação e contexto independentes, sem ferramentas.
- PDF ou modelo incompatível com ABI/API → spike antes de integrar sem escrita em claro ou alteração silenciosa de compatibilidade.
- Payloads cifrados limitam SQL → dados pequenos, índices opacos e busca limitada em memória.
- Exclusão e workers concorrem → tombstone, bloqueio transacional e reconciliação.
- Delete não sobrescreve fisicamente flash → documentar limite sem prometer apagamento físico seguro.

## Migration Plan

1. Validar corpus/contratos e spikes de PDF/modelo antes de conectar conteúdo à persistência.
2. Criar schema Room v2 exportado e migração não destrutiva, preservando IDs/correções e avaliações antigas em execução legada da regra 1.0.0, sem inferir revisão inexistente.
3. Migrar base antiga para destino com payloads protegidos, verificar contagens/integridade e trocar com marcador recuperável. Só após sucesso remover base antiga/sidecars. Testar interrupções em cópia e troca; não gravar novos payloads sensíveis em claro.
4. Centralizar abertura para Hilt/workers incluindo DeletionWorker e bloquear escritores durante migração. Sem fallback destrutivo. Dados já escritos em claro anteriormente não têm eliminação física em flash garantida.
5. Integrar fluxo/corpus e inferência P0, atualizar governança, wiring, README e CI. Exemplos públicos continuam independentes.
6. Testar instalação limpa offline, inferência real, dois modos, revisão, retomada, falhas, reabertura e exclusão no S25. Registrar Android/RAM reais; matriz 4 GB pendente.
7. Não fazer downgrade automático para v1 em claro. Falha antes da troca permite retomar migração ou descarte explícito, nunca processamento sobre base incompleta.

## Open Questions

- Versão exata de runtime e resultado do spike no S25, incluindo reset/cancelamento e orçamento de thinking. A decisão de modelo é documentada antes do empacotamento e mantém o mesmo contrato de evidência.
- Biblioteca PDF que passar os critérios de licença, memória, ABI/API e acesso em memória.
- Android/RAM efetivos do S25, medições e URLs de exemplos públicos elegíveis. Exemplos públicos não bloqueiam os sintéticos.

## References

- [LFM card e arquivos na revisão consultada](https://huggingface.co/litert-community/LFM2.5-1.2B-Thinking/tree/f8b5b3a3eea2e3859e821ad9d9222c64d649eff7).
- [Manifesto LFM](https://huggingface.co/litert-community/LFM2.5-1.2B-Thinking/blob/f8b5b3a3eea2e3859e821ad9d9222c64d649eff7/litertlm_manifest.json).
- [Licença LFM](https://huggingface.co/litert-community/LFM2.5-1.2B-Thinking/blob/f8b5b3a3eea2e3859e821ad9d9222c64d649eff7/LICENSE).
- [Qwen na revisão consultada](https://huggingface.co/litert-community/Qwen3-0.6B/tree/e6687177e00897731e7716267eebd4eba0dde7c2).
- [LiteRT-LM Kotlin](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md).
