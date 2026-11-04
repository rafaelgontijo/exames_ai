# Auditoria dos derivados clínicos

Inventário de código e testes sintéticos em 27/10/2026. Todas as verificações
novas ocorreram no emulador API 36; não representam validação clínica ou ensaio
novo de modelo real no S25.

| Categoria | Local atual | Exclusão e verificação |
| --- | --- | --- |
| Original privado | `noBackupFilesDir/artifacts/<documentId>/original.enc` | Limpeza recursiva sob barreira permanente; diretório ausente após ack. |
| Prévia e OCR | Bitmap/texto/spans em memória nos adaptadores de documentos | Recursos fechados por página. Fixtures de derivados persistidos usam arquivos AES-GCM no mesmo diretório, todos removidos. |
| Arquivos derivados/interrompidos | `<documentId>/*.enc`, incluindo `pending-*.enc` | Diretório inteiro é removido, sem depender da lista de nomes conhecidos. Escritor ativo termina antes da limpeza; novos escritores são bloqueados. |
| Observações, evidência e referências/candidatos | Payloads Room | Cascade por documento/execução e verificações de elegibilidade na mesma transação. |
| Avaliações e checkpoints | Payloads Room | Removidos com a execução, inclusive versões/participação de inferência. |
| Gabaritos, correções e comparações dos dois tipos | Payloads Room | Relações locais em cascade; tentativas tardias de append rejeitadas. |
| Configuração/tentativa e traces de inferência | Configuração em run/checkpoint; final gerado permanece em memória | Registros estruturados seguem a cascade. Fixtures `trace-attempt`/`trace-final` no store são removidas. Pensamento não é persistido. Traces futuros de 4.13 devem usar esses mesmos destinos protegidos. |
| Modelos estáticos compartilhados | Asset empacotado e `noBackupFilesDir/models` | Fora da raiz clínica; sentinela de pesos preservada com hash igual após excluir documento. Nenhum peso real foi carregado neste ensaio. |
| Preferências gerais | DataStore, somente tema | Não recebem campos clínicos e não são apagadas por documento. |
| Journals e barreiras operacionais | `pending-imports`, journal de migração, tombstones SQL, `artifact-guards` | Somente IDs/fases/byte de exclusão; não são cópias clínicas. O worker reconcilia imports antes da limpeza. Tombstones e barreiras permanecem para impedir ressurreição. `.key` apenas serializa acesso ao Keystore; não contém chave. |

## Evidência

`DeletionCoverageTest` cria original PDF com canário dinâmico, arquivos de prévia,
OCR, tentativa/final e arquivo cifrado parcial. Também cria duas execuções,
observação/evidência/candidatos, referência, avaliação, checkpoint, correção,
gabarito e comparações END_TO_END/EVALUATOR_ISOLATED. Após excluir e confirmar
a outbox, verifica ausência de todas as relações/diretório, inclusive após
reabrir a base, e rejeita oito categorias de escritor tardio. Outro documento,
seu artefato e os pesos sintéticos permanecem íntegros.

O canário único é procurado, antes e depois, em noBackupFilesDir, filesDir,
cacheDir, codeCacheDir e diretório de bancos/journals. Não aparece em claro.
O teste não grava o canário no relatório. Revisão do código de produção também
não encontrou escrita clínica em preferências nem chamadas de logs nos quatro
adaptadores auditados. LiteRT-LM está configurado com `:nocache` e logs nativos
desabilitados; o teste de derivados não executa o modelo ou audita drivers GPU.

## Chaves e limites

Leitura de artefato não cria chave ausente. Uma escrita só cria a chave inicial
se o alias não existir e não houver ciphertext no store. Arquivos inacessíveis
não são sobrescritos para simular recuperação. A primeira criação é serializada
entre instâncias/processos por FileLock; testes cobrem duas instâncias concorrentes.
Chave indisponível retorna erro fixo sem causa/conteúdo clínico.

Perda da chave de payload impede reabertura da base e preserva intenção de
exclusão pendente, banco e fonte cifrada. Os testes usam aliases/raízes isolados,
sem apagar chaves ou pesos de outros documentos.

A auditoria verifica remoção lógica e arquivos possuídos pelo app, não apagamento
irrecuperável de páginas SQLite/flash, limpeza imediata da memória de JNI/OCR ou
comportamento de todo driver nativo. Esses limites continuam explícitos. UI e
aceitação real do runtime permanecem nas tarefas próprias.
