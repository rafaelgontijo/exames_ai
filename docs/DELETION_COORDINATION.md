# Exclusão coordenada e barreiras permanentes

## Ordem e garantias

`ProtectedValidationRepository.deleteCompletely` grava, na mesma transação,
o tombstone permanente do documento, a entrada de outbox e os IDs dos trabalhos
a cancelar, antes de remover documento e registros associados por cascade.
Assim o documento desaparece imediatamente e todos os escritores SQL encontram
o tombstone ou a ausência da execução. A captura dos IDs antecede a cascade.

Depois da transação, tenta cancelar os trabalhos e agenda limpeza. Falha ou
interrupção nessa etapa não desfaz a intenção: o worker periódico e a
reconciliação retomam o cancelamento. Uma chamada de exclusão confirma a intenção
e o ocultamento; não equivale à confirmação de limpeza de arquivos concluída.

`ProtectedDeletionCleanup` cancela os trabalhos preservados na outbox, estabelece
a barreira de arquivos, remove o diretório de artefatos e só então confirma a
outbox. Também cancela trabalhos etiquetados de execuções já ausentes, incluindo
resíduos de versões anteriores. Falha de cancelamento/arquivos mantém a outbox.
Uma interrupção após apagar arquivos e antes do ack é segura para repetir.

`EncryptedArtifactStore` usa `ArtifactDocumentLocks` em cada leitura, escrita e
remoção. Um lock por documento no processo e um FileLock serializam instâncias
e protegem a seção de arquivos. A limpeza espera escritores que já entraram,
sincroniza um byte permanente de exclusão, apaga os arquivos e sincroniza o
diretório antes do ack. Novos escritores/leitores são rejeitados mesmo após
reabrir o store ou confirmar a outbox. Marcador inválido falha fechado.

O tombstone SQL não é removido com a outbox; o marcador de arquivos também não.
Retêm somente IDs operacionais opacos e um byte, sem texto clínico, URI ou
chave. IDs excluídos nunca são reutilizados. Pesos estáticos compartilhados do
modelo ficam fora da raiz de artefatos clínicos.

## Room 3 e compatibilidade

Room 3 acrescenta `document_tombstones` e `deletion_work`; as tabelas/payloads
existentes não são reescritos. A versão de AAD dos payloads continua `2` e o
formato dos arquivos cifrados continua `1`. Migração explícita 2→3 cria os novos
registros permanentes para outboxes conhecidas e é validada pelo Room, sem
fallback destrutivo. A migração recuperável de v1 passa a instalar o destino
atual e também aceita staging protegido v2 interrompido, aplicando a migração
explícita antes de verificar/instalar.

IDs de documentos cujo histórico de exclusão já foi removido por versões
antigas não podem ser reconstruídos. A migração conserva intenções ainda
existentes; novos tombstones não são apagados. Não cria recuperação fictícia
de dados ou histórico ausente.

## Verificação e limites

Instrumentados sintéticos exercitam página em andamento, inferência simulada
ainda aguardando resposta, escritor de arquivo bloqueado, falha de cancelamento,
interrupção após apagar arquivos, reabertura, callback tardio, marcador inválido
e migração 2→3 com ciphertext preservado. A regressão de migração v1 e seus
pontos de interrupção também deve continuar aprovada.

Não comprova encerramento imediato de JNI/OCR, execução durante force-stop,
apagamento físico irrecuperável em flash nem limpeza imediata de buffers já
entregues ao runtime. As barreiras impedem publicação/persistência posterior;
deadlines e isolamento de inferência permanecem em 4.12. Inventário e procedimentos
verificados em [DERIVATIVE_AUDIT.md](DERIVATIVE_AUDIT.md) e
[PROCESSING_RECOVERY.md](PROCESSING_RECOVERY.md); integração de UI continua em 7.4. Testes no S25 ficam
para a etapa final conforme orientação do usuário.
