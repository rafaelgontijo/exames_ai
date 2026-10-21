# Schema protegido v2

`ProtectedDatabase` define o destino da migração v1→v2. A abertura de produção usa `ProtectedDatabaseFactory`, compartilhada por Hilt e pelo worker de limpeza. O schema legado permanece para migração/fixtures, sem abertura independente em produção.

Tabelas: documentos, execuções, observações, referências, avaliações, correções, versões de gabarito, comparações, checkpoints e outbox de exclusão. Os campos sensíveis ficam em `payload` BLOB autenticado. Em claro ficam identificadores opacos, relações, ordem/página, timestamps e estados operacionais necessários ao agendamento; isso não equivale a criptografar todo o contêiner SQLite.

Observações pertencem a uma execução e ao mesmo documento por chave estrangeira composta. Comparações pertencem a uma execução e a um gabarito do mesmo documento. Avaliações são vinculadas a observação/execução. As tabelas de saída/auditoria usam INSERT com ABORT: um segundo registro com a mesma identidade falha em vez de substituir o original. Checkpoints operacionais podem ser atualizados, sem sobrescrever a saída automática.

Excluir remove o documento e seus registros em cascata na mesma transação que inclui a intenção na outbox. A outbox sobrevive ao documento e só será confirmada após a limpeza dos arquivos. A coordenação de writers/arquivos e a integração com a UI continuam nas tarefas próprias.

O schema exportado fica em `data-local/schemas/br.com.exames.datalocal.ProtectedDatabase/2.json`. Os testes instrumentados de schema verificam preservação de saída em colisão de identidade, proibição de associação entre documentos e cascata de todas as relações com outbox preservada.

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

A UI, o pipeline de extração e o controle completo de escritores/arquivos ainda dependem das tarefas seguintes; a fundação de armazenamento não representa o MVP integrado.
