# multimodal-vision-extraction Delta Specification

## MODIFIED Requirements

### Requirement: Pinned multimodal weights run offline in isolated per-request processes

A distribuição padrão SHALL utilizar pesos multimodais fixados por revisão, tamanho e SHA-256 sob licença Apache-2.0 (`gemma-4-E2B-it.litertlm`), entregues sob demanda em background para armazenamento privado sem backup (`noBackupFilesDir`) em vez de embarcados no APK padrão. A permissão `INTERNET` SHALL ser restrita exclusivamente ao download verificado dos pesos locais e ao fallback privado na nuvem mediante consentimento explícito (`opt-in`), mantendo `usesCleartextTraffic="false"` e proibindo telemetria ou bibliotecas de analytics. No caminho local, cada faixa SHALL executar em `Engine` e processo filho `:inference` novos, com payload de imagem transferido exclusivamente em memória dentro de limite estrito de bytes, respeitando prazos de carga/geração e cancelamento cooperativo.

#### Scenario: Transfer band image to isolated inference process without disk plaintext
- **WHEN** o app envia uma faixa renderizada para o processo `:inference` no caminho local
- **THEN** os bytes da imagem são limitados em tamanho e trafegam apenas em memória, sem gravação de arquivos temporários de imagem clínica no armazenamento e sem registro de conteúdo clínico em logs

#### Scenario: Cancel extraction during banded vision inference
- **WHEN** a pessoa cancela o processamento enquanto uma faixa está carregando ou gerando (no processo local `:inference` ou na chamada HTTPS do fallback em nuvem)
- **THEN** a execução em andamento é encerrada dentro do prazo de limpeza, nenhuma resposta tardia é publicada e o estado ativo é limpo sem corromper o histórico existente

#### Scenario: Device lacks space or multimodal asset fails integrity check
- **WHEN** os pesos multimodais locais estão ausentes, falham na verificação de integridade SHA-256 ou não há espaço livre suficiente para preparação atômica no diretório privado
- **THEN** o app não fabrica resultados, sinaliza claramente o motivo operacional seguro e oferece as ações aplicáveis (baixar/reparar pesos locais ou, mediante consentimento explícito `opt-in`, usar o fallback privado na nuvem)
