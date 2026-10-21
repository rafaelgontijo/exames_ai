# Spec Delta

## Purpose

Preservar documentos, gabaritos, execuções e correções para reabertura local com proteção em repouso e exclusão coordenada e recuperável.

## ADDED Requirements

### Requirement: Persisted clinical content is encrypted and private
O aplicativo SHALL criptografar arquivos, campos sensíveis, gabaritos, correções e derivados persistidos, inclusive temporários, com chaves protegidas pelo Android Keystore. SHALL desabilitar backup/transferência automática e não expor conteúdo sensível em logs, rotas ou preferências gerais.

#### Scenario: Inspect persistent storage after processing
- **WHEN** um teste inspeciona arquivos, banco, journals e caches
- **THEN** não encontra marcadores sensíveis em claro e os backups permanecem excluídos

### Requirement: History survives application restart
O histórico SHALL reabrir documentos, execuções, correções, gabaritos e comparações após reinício sem arquivo externo, distinguindo saída automática, corrigida e parcial.

#### Scenario: Reopen an execution after restart
- **WHEN** o usuário reabre execução no histórico após encerrar o app
- **THEN** encontra mesma saída, versão de gabarito e fonte, com correções identificadas separadamente

### Requirement: Deletion covers all owned derivatives and prevents resurrection
Excluir SHALL ocultar imediatamente o documento e registrar intenção durável de apagar original, OCR/texto, prévias, observações, execuções, gabaritos locais associados, correções e comparações. Trabalhos concorrentes SHALL NOT recriar o documento e falhas de limpeza SHALL permitir retentativa sem reexposição.

#### Scenario: Delete during processing
- **WHEN** o documento é excluído durante OCR
- **THEN** desaparece, impede gravações concorrentes e remove derivados por limpeza recuperável

#### Scenario: Crash interrupts file cleanup
- **WHEN** há falha após ocultar registros e antes de apagar arquivos
- **THEN** a intenção persiste e a limpeza retoma sem restaurar o documento

### Requirement: Key failures do not trigger insecure fallback
Falha, invalidação ou perda de chave SHALL produzir estado de indisponibilidade. O app SHALL NOT gravar em claro, recriar base vazia silenciosamente ou afirmar recuperar dados inacessíveis; SHALL permitir descarte explícito.

#### Scenario: Encryption key is invalidated
- **WHEN** a chave não está disponível
- **THEN** comunica indisponibilidade sem sucesso falso, fallback em claro ou apagamento silencioso

### Requirement: Inference traces follow document confidentiality and deletion
Prompts, respostas e caches clínicos SHALL ficar em memória ou armazenamento criptografado vinculado à exclusão documental. Pesos estáticos SHALL ser privados e íntegros, sem reter sessões, e não precisam de criptografia clínica. Pensamento intermediário SHALL NOT ser persistido.

#### Scenario: Delete a document after inference
- **WHEN** o usuário exclui documento processado com IA
- **THEN** descarta sessões e derivados sem vazamento em logs/caches, conservando somente pesos estáticos compartilhados
