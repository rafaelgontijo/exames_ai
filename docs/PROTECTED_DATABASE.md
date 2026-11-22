# Schema protegido v3

`ProtectedDatabase` define o schema protegido atual v3. A abertura de produção usa `ProtectedDatabaseFactory`, compartilhada por Hilt e pelo worker de limpeza. A migração v1 por cópia/troca e a atualização explícita 2→3 preservam os payloads; a versão de AAD continua `2`. O schema legado permanece para migração/fixtures, sem abertura independente em produção.

Tabelas: documentos, execuções, observações, referências, avaliações, correções, versões de gabarito, comparações, checkpoints, outbox de exclusão, tombstones permanentes e trabalhos a cancelar. Os campos sensíveis ficam em `payload` BLOB autenticado. Em claro ficam identificadores opacos, relações, ordem/página, timestamps e estados operacionais necessários ao agendamento; isso não equivale a criptografar todo o contêiner SQLite.

Observações pertencem a uma execução e ao mesmo documento por chave estrangeira composta. Comparações pertencem a uma execução e a um gabarito do mesmo documento. Avaliações são vinculadas a observação/execução. As tabelas de saída/auditoria usam INSERT com ABORT: um segundo registro com a mesma identidade falha em vez de substituir o original. Checkpoints operacionais podem ser atualizados, sem sobrescrever a saída automática.

Excluir captura trabalhos, grava tombstone/outbox e remove o documento e seus registros em cascata na mesma transação. A outbox só é confirmada após cancelamento, barreira durável de arquivos e limpeza. O tombstone permanece para impedir ressurreição depois do ack. Detalhes em [DELETION_COORDINATION.md](DELETION_COORDINATION.md); a integração com UI permanece pendente.

Os schemas exportados ficam em `data-local/schemas/br.com.exames.datalocal.ProtectedDatabase/2.json` e `3.json`. Os testes instrumentados verificam colisões/associação, cascata, migração 2→3 preservando ciphertext, tombstones e bloqueio de escritores após o ack.

## Migração por cópia e troca

`ProtectedDatabaseFactory` prepara uma base de destino privada com payloads AES-GCM. Mantém a fonte v1 intacta durante a cópia, verifica relações, contagens e autenticação/round-trip dos payloads antes de instalar o destino. Preserva IDs e conteúdo bruto legado; cria execução identificada como legado para avaliações 1.0.0, sem reclassificá-las pela regra nova.

O journal operacional privado tem fases INITIALIZING, COPYING, READY, INSTALLING e INSTALLED, com escrita/rename/fsync. Após início da cópia, chave ausente não é regenerada. Arquivo de staging parcial pode ser reconstruído usando a fonte; interrupções na troca retomam pelas identidades do journal, fonte/backup e destino. A fonte é removida apenas após validar o destino protegido. O banco novo não grava payload clínico em claro, nem usa fallback destrutivo.

Aplicação e workers usam esse mesmo ponto de abertura. Na migração, leitores/escritores legados precisam estar encerrados e o WAL checkpointado antes da troca. Dados previamente escritos em claro não têm eliminação física de blocos flash garantida; a exclusão de arquivos não faz essa promessa.

```bash
source /workspace/.android-dev/env.sh
./gradlew :data-local:connectedDebugAndroidTest
```

Os testes de migração incluem conteúdo bruto/correções/versão de regra, ausência de marcadores em claro nos arquivos finais, reabertura, interrupção após cópia e após cada rename e perda de chave sem recriar base vazia. Os resultados efetivamente executados ficam em `docs/VALIDACAO.md`.

## Repositórios de validação

`ValidationRepository` mantém leitura por execução explícita, commit transacional de página, correções append-only, snapshots de gabarito com versão/hash e comparações vinculadas à versão exata. O adaptador `ProtectedValidationRepository` é fornecido por Hilt e também satisfaz o contrato de documentos. Campos clínicos e dados de revisão passam pelo codec; o repositório não calcula classificações.

O commit de página insere observações, referências, avaliações e checkpoint na mesma transação. Tentativa repetida de página concluída não substitui a saída original; colisão durante a escrita reverte a página inteira. Correção com valor anterior obsoleto é rejeitada, inclusive após uma correção para null. Reexecução usa outra identidade e pode apontar para a original. Concluir execução exige todas as páginas concluídas; transição de execução final para RUNNING é rejeitada.

A UI e a aceitação completa de inferência ainda dependem das tarefas seguintes; a fundação de armazenamento não representa o MVP integrado. O pipeline e seus limites atuais estão em [PROCESSING_PIPELINE.md](PROCESSING_PIPELINE.md).
