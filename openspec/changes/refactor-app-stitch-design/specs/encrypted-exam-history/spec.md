# Spec Delta

## Purpose

Garante o armazenamento privado, local e criptografado de múltiplos exames em PDF e seus resultados extraídos no aparelho, permitindo listar, reabrir e excluir exames individualmente sem expor dados clínicos ou PII fora de criptografia autenticada.

## ADDED Requirements

### Requirement: Store multiple imported exams privately with authenticated encryption

O armazenamento local SHALL persistir múltiplos exames importados (PDF original e resultado da extração) de forma privada e criptografada com chaves gerenciadas pelo Android Keystore, mantendo um índice igualmente cifrado contendo apenas identificadores opacos, data de importação, contagem de páginas, resumo de contagens por estado e sinalizador de leitura interrompida.

#### Scenario: Import a second exam without overwriting the first
- **WHEN** a pessoa já possui um exame salvo e importa um novo PDF digital com sucesso
- **THEN** ambos os exames ficam salvos em armazenamento privado cifrado e aparecem ordenados do mais recente para o mais antigo nas listas `Meus exames` e `Exames`

#### Scenario: Reopen any saved exam and its original PDF pages
- **WHEN** a pessoa seleciona um exame salvo anteriormente na lista local
- **THEN** o aplicativo descriptografa em memória os resultados daquela extração e permite abrir as páginas originais do respectivo PDF na tela `Ver no laudo`

### Requirement: Migrate existing single-exam storage seamlessly on upgrade

Ao atualizar uma instalação que utilizava o armazenamento de exame único anterior, o aplicativo SHALL migrar automaticamente a extração atual existente para o histórico cifrado multi-exame sem perder o PDF nem os resultados, e SHALL manter intocados eventuais arquivos de instalações legadas anteriores sem causar falha de abertura.

#### Scenario: Upgrade from single-exam version with an active extraction
- **WHEN** o aplicativo atualizado abre pela primeira vez encontrando o ponteiro de exame único anterior com artefatos cifrados válidos
- **THEN** o exame existente é incorporado ao índice cifrado do histórico local e fica imediatamente acessível nas telas `Início` e `Exames`

### Requirement: Support individual deletion, clear-all, and safe cancellation cleanup

O armazenamento local SHALL permitir excluir um exame específico (removendo seu PDF original, sua extração e sua entrada no índice de forma atômica), excluir todos os exames salvos mediante confirmação e descartar apenas os artefatos da leitura em andamento quando uma extração for cancelada.

#### Scenario: Delete a single exam from history
- **WHEN** a pessoa confirma a exclusão de um exame específico
- **THEN** o aplicativo remove definitivamente o PDF cifrado, os resultados e a entrada do índice daquele exame, preservando os demais exames salvos

#### Scenario: Cancel an extraction while older exams exist
- **WHEN** a pessoa cancela a leitura de um novo PDF enquanto outros exames já estão salvos
- **THEN** apenas a cópia temporária da leitura cancelada é removida e o histórico existente permanece intacto

### Requirement: Prevent clinical plaintext exposure in filenames, logs, backups, and state

O histórico local SHALL manter backups desabilitados, SHALL usar somente identificadores opacos em nomes de arquivos e rotas, e SHALL impedir que nomes de exames, valores clínicos, referências ou URIs sejam gravados em arquivos sem criptografia, logs de produção ou estado de navegação.

#### Scenario: Inspect storage directory and navigation arguments
- **WHEN** o diretório privado do aplicativo e os argumentos de navegação são inspecionados após salvar múltiplos exames
- **THEN** todos os arquivos de conteúdo e índice estão criptografados, nenhum nome de arquivo ou argumento de rota contém texto clínico ou URI, e o manifesto mantém `allowBackup="false"`
