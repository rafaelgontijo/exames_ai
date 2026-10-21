# Tasks

## 1. Contratos e corpus reproduzível

- [ ] 1.1 Adicionar contratos JVM de caso/família, gabarito, execução, evidência e comparação; verificar serialização, IDs estáveis e independência de Android em `:domain:test`.
- [ ] 1.2 Criar gabaritos anteriores à renderização para cinco analitos e dois layouts, cobrindo limites, censura, ambiguidades, unidades, contexto, faixas ausentes/concorrentes e qualitativos; verificar matriz versionada em `validation/corpus/`.
- [ ] 1.3 Implementar gerador com ferramentas/fontes/seeds fixados para PDF textual, escaneado e JPEG/PNG, incluindo rotação/degradação; verificar semântica, hashes e partição por família.
- [ ] 1.4 Definir pacote local de corpus/gabarito com schema, hash, tamanho, IDs e caminhos validados; verificar testes de entradas inválidas, path traversal e exemplo sem revisão.
- [ ] 1.5 Documentar geração e inclusão/revisão de exemplos públicos com URL/data/condições de uso/hash fora do APK; verificar procedimento demonstrativo e execução sintética sem cobertura pública.

## 2. Persistência protegida e histórico imutável

- [ ] 2.1 Implementar codec AES-GCM/Keystore com IV único e AAD por entidade/ID/versão; verificar round-trip, adulteração, troca de payload e chave indisponível em testes instrumentados.
- [ ] 2.2 Criar Room v2 com execuções/checkpoints/evidências/gabaritos/correções/comparações imutáveis e apenas metadados operacionais em claro; verificar schema exportado, relações e ausência de upsert destrutivo da saída original.
- [ ] 2.3 Implementar migração v1→v2 em destino protegido e troca recuperável; verificar IDs, contagens, correções, regra legada e interrupções em cópia/troca antes de remover base antiga/sidecars.
- [ ] 2.4 Centralizar abertura/migração para Hilt e workers incluindo DeletionWorker; verificar ausência de caminho legado, fallback destrutivo ou recriação silenciosa após falha de chave.
- [ ] 2.5 Implementar repositórios de documento/execução/observação/avaliação/gabarito/comparação; verificar reabertura e preservação da saída/configuração/gabarito originais após correções e reexecuções.
- [ ] 2.6 Inspecionar banco, WAL/journals, caches e arquivos com marcadores sintéticos; verificar ausência em claro e documentar proteção de payloads, metadados expostos e limites de descarte físico.

## 3. Importação, PDF e OCR

- [ ] 3.1 Concluir spike PDF de buffer autenticado para texto/raster sem URI externa ou temporário persistente em claro; verificar licença, minSdk 23, ABI/16 KiB, PDF híbrido e memória em decisão técnica.
- [ ] 3.2 Integrar SAF à cópia criptografada, assinatura, hash e limite real; verificar MIME incorreto, tamanho ausente/enganoso, truncamento, revogação de URI e limpeza após interrupção.
- [ ] 3.3 Adaptar texto/raster à cópia privada e coordenadas comuns da prévia; verificar destaque correto em PDF textual, escaneado, híbrido e rotacionado.
- [ ] 3.4 Conectar OCR empacotado, EXIF e decodificação limitada; verificar JPEG/PNG rotacionados, imagem enorme, PDF sem texto e confiança desconhecida preservada como null.
- [ ] 3.5 Configurar limites iniciais de 20 MiB, 20 páginas e raster até 2048 pixels no maior lado; verificar rejeição, fechamento de recursos e alocações limitadas, documentando parâmetros técnicos ajustáveis.

## 4. Parser, LiteRT-LM e avaliação conservadora

- [ ] 4.1 Implementar cinco analitos/abreviações e identificação dos dois layouts; verificar campos/proveniência contra fixtures e sinalização de conteúdo fora do escopo.
- [ ] 4.2 Implementar associação de colunas/faixas e contexto explícito; verificar tabelas densas, rodapés, duplicatas e contexto ausente sem seleção silenciosa.
- [ ] 4.3 Introduzir REF_NUMERIC/2.0.0 sem confirmação prévia e com revisão separada; verificar classificação inequívoca não revisada sem confirmed=true artificial.
- [ ] 4.4 Preservar/ampliar testes de BigDecimal, limites, comparadores, números ambíguos, censura, unidades, faixas e contexto; verificar `./gradlew :domain:test` e precedência de abstenção inclusive após confirmação genérica.
- [ ] 4.5 Documentar nova regra e layouts versus 1.0.0 legada; verificar exemplos executáveis sem referências clínicas externas.
- [ ] 4.6 Verificar o arquivo LFM INT4 na revisão `f8b5b3a3eea2e3859e821ad9d9222c64d649eff7`, confrontar tamanho/SHA-256 real com metadados e documentar licença/redistribuição e alternativa Qwen; verificar manifesto fixado sem baixar pesos para o Git comum.
- [ ] 4.7 Fixar AAR LiteRT-LM e executar spike no S25 com LFM CPU INT4 e ensaio GPU separado se pertinente; verificar carga real, JSON em pt-BR, evidência, licença, API/ABI/16 KiB, memória e latência, registrando escolha LFM ou Qwen com justificativa antes do empacotamento.
- [ ] 4.8 Promover/renomear o stub para `:inference-local`, contrato puro e DI, ativo na bancada compatível; verificar execução real, seleção sem IA e ausência de rede, sem elevar minSdk silenciosamente.
- [ ] 4.9 Implementar staging e distribuição de um modelo no APK, cópia privada atômica e hash/espaço; verificar instalação limpa em modo avião, ausência/corrupção e falta de espaço sem download posterior.
- [ ] 4.10 Implementar prompt/schema JSON com texto/evidência, sem tools e contexto isolado; verificar canal final separado, pensamento não persistido, orçamento de geração/raciocínio do LFM, truncamento e instruções maliciosas, sem presumir thinking desligável.
- [ ] 4.11 Validar spans, associação e contexto antes da avaliação; verificar rejeição de faixa inventada, linha errada, analito não suportado, divergência e ambiguidades anteriores preservadas.
- [ ] 4.12 Implementar limites de tokens/tempo/concorrência, engine nova por requisição e cancelamento/fallback; verificar contexto excedido, timeout JNI, estado ShortConv/KV entre prefixos iguais, exclusão e callbacks tardios, isolando processo se necessário.
- [ ] 4.13 Persistir configuração/tentativa/sucesso/fallback com hash/runtime/prompt/schema/backend; verificar rastreabilidade após atualização, ausência de conteúdo em logs/caches em claro e limpeza de derivados sem remover pesos compartilhados.
- [ ] 4.14 Atualizar AGENTS.md e documentação para inferência P0 autorizada; verificar eliminação da restrição anterior de IA fora do MVP, conservando domínio puro, evidência, privacidade e motor determinístico.

## 5. Pipeline recuperável e exclusão concorrente

- [ ] 5.1 Implementar worker por execução com IDs opacos e checkpoint transacional por página/modo sem rede; verificar retomada idempotente após morte do processo sem duplicação.
- [ ] 5.2 Implementar falha parcial, cancelamento, retentativa e reconciliação de órfãos; verificar página corrompida entre válidas e estado incompleto visível.
- [ ] 5.3 Integrar tombstone/outbox, barreira transacional e cancelamento à exclusão; verificar corrida durante OCR/inferência e reinício antes da limpeza sem ressurreição.
- [ ] 5.4 Abranger original, prévias, OCR, observações, execuções, gabaritos associados, correções, comparações e traces na limpeza; verificar ausência de derivados e bloqueio de escritores tardios antes da confirmação.
- [ ] 5.5 Documentar estados/garantias de retomada e exclusão, force-stop e chave perdida; verificar procedimentos com testes de falha/recuperação.

## 6. Comparação e métricas

- [ ] 6.1 Implementar pareamento um-para-um e comparação bruta/canônica; verificar ausentes, extras, duplicatas e faixas na observação errada sem parear pelo valor esperado.
- [ ] 6.2 Implementar contagens/denominadores, cobertura, abstenção e erro fora→dentro; verificar vazio, cobertura zero, parciais e anormais ausentes contra cálculos explícitos.
- [ ] 6.3 Medir avaliador isolado sobre gabarito e ponta a ponta sobre extração; verificar que mudança exclusiva do gabarito não modifica processamento original.
- [ ] 6.4 Gerar relatórios locais por origem/formato/analito/layout/partição e versões; verificar exclusão de gabaritos pendentes, N/A vazio e separação de métricas automáticas/corrigidas.
- [ ] 6.5 Medir tempo monotônico, falhas e memória disponível com identidade do aparelho/Android; verificar relatório com método/limites documentados e ausência de telemetria/log clínico.
- [ ] 6.6 Comparar pares sem IA/assistido sobre documentos/gabaritos/regra idênticos, incluindo divergências, erros, cobertura, carga fria, tempo até JSON final, tokens thought/final, memória e fallback; verificar que sucesso do parser não é sucesso de IA e cobertura não oculta novos erros.

## 7. Bancada Android de revisão

- [ ] 7.1 Conectar importação/pacotes e pipeline com casos de uso/Hilt/StateFlow; verificar estados Compose e ausência de dados sensíveis em rotas/SavedStateHandle.
- [ ] 7.2 Substituir estado vazio por campos, motivos, gabarito e fonte destacada; verificar página/região e distinção entre automático, revisado, NEEDS_REVIEW e NOT_CLASSIFIABLE.
- [ ] 7.3 Separar correção de extração, revisão de gabarito e reavaliação/reexecução; verificar antes/depois, comparação por versão e métricas originais imutáveis.
- [ ] 7.4 Conectar histórico/reabertura/exclusão; verificar reinício sem fonte externa, desaparecimento imediato e limpeza recuperável.
- [ ] 7.5 Disponibilizar relatório e revisar acessibilidade; verificar TalkBack, fonte ampliada, contraste e estados além de cor, documentando o fluxo do revisor.
- [ ] 7.6 Exibir modo, preparação de modelo e fallback; verificar distinção entre uso real e indisponibilidade sem expor pensamento ou detalhes desnecessários na revisão.

## 8. Integração e dispositivo

- [ ] 8.1 Executar `./gradlew assembleDebug test :app:assembleDebugAndroidTest` e adaptar CI a corpus/modelo/hash/manifest; verificar integração real e ausência de INTERNET, clientes de rede e backup, sem aceitar stub como validação da IA.
- [ ] 8.2 Executar instalação limpa com modelo distribuído e rede bloqueada antes da primeira abertura, formatos/dois modos, revisão/reinício/exclusão; verificar instrumentados e identificar medições indisponíveis como pendentes.
- [ ] 8.3 Executar inferência real do modelo selecionado e A/B no S25, com aparelho/Android/RAM, revisão/hash/runtime/prompt/backend e métricas por estrato; verificar relatório reproduzível sem concluir com fallback exclusivo e manter 4 GB pendente.
- [ ] 8.4 Atualizar README e docs/VALIDACAO com resultados realmente executados, comandos/corpus/versões/limites/pendências e escolha de modelo; verificar distinção entre evidência técnica, revisão documental e ausência de validação clínica.
