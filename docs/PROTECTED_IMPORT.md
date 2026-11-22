# Importação privada (bancada interna)

`ProtectedDocumentImporter`, fornecido por Hilt, integra `SafDocumentReader` ao
store AES-GCM e ao repositório protegido. A ligação com a tela/pipeline continua
na tarefa 7.1; o seletor antigo ainda faz somente inspeção.

A leitura abre a URI `content:` uma vez e fecha o stream mesmo em falha ou
cancelamento. Não retém grant, nome ou URI. O tamanho informado pelo provedor
não governa a aceitação: lê no máximo 20 MiB + 1 byte e rejeita excesso real.
O buffer em memória é limitado a 20 MiB; não há PDF/imagem temporário em claro.
A implementação pode manter cópias transitórias desse buffer durante a leitura;
isso não constitui limite de memória total do processo.

A assinatura determina PDF, JPEG ou PNG. MIME específico divergente é rejeitado;
MIME ausente ou `application/octet-stream` usa a assinatura. A validação de
transporte exige terminador PDF/JPEG e estrutura limitada dos chunks PNG até
IEND. Não substitui decodificação: integridade interna de PDF, CRC/decodificação
de PNG/JPEG, número de páginas e dimensões continuam responsabilidade dos
adaptadores/pipeline. Um documento registrado como IMPORTED ainda pode falhar
na extração; não significa conteúdo integralmente válido ou avaliação concluída.
O SHA-256 corresponde aos bytes efetivamente recebidos.

Depois da validação, um UUID identifica o documento. Um journal privado contém
somente esse ID opaco, antes de qualquer escrita de artefato. O store escreve um
temporário cifrado e faz rename; somente depois o repositório publica metadados
protegidos. `uriLocal=artifact:original` referencia o artefato privado, sem caminho
ou URI externa. O buffer final é zerado ao sair da etapa de persistência.

A publicação da fonte/metadados é uma seção curta não cancelável; o cancelamento
durante leitura não publica documento. Isso não promete cancelamento de leitura
bloqueada dentro de um provedor de terceiros. Após morte do processo, a
reconciliação consulta cada ID: preserva fontes já registradas, remove diretórios
órfãos (inclusive temporários cifrados) e remove o journal. Se banco/chave não
estiverem disponíveis, conserva os dados cifrados e permite retentativa. O mutex
serializa importação e reconciliação no processo atual; não é um lock multiprocesso.
A tarefa de limpeza é acionada na abertura do app e periodicamente, além da
reconciliação antes da próxima importação. Force-stop impede workers até o app
poder executar novamente. Não há promessa de transação única entre SQLite e
filesystem nem de descarte físico de flash.

Em 27/10/2026, seis testes JVM passaram. No S25 SM-S931B / Android 17, sete novos
testes instrumentados passaram junto dos 12 testes existentes de persistência.
Cobrem tamanho ausente/enganoso, MIME genérico/divergente, truncamento,
revogação, fonte reaberta após revogação, ausência de marcador sintético em claro,
falha de publicação, interrupção simulada antes/depois de commit e banco
indisponível. A interrupção é simulada por journal/arquivos; não é teste de queda
de energia durante fsync. Somente dados sintéticos; nenhuma validação clínica.
