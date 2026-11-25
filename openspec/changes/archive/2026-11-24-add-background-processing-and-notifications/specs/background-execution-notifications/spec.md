# background-execution-notifications Specification

## Purpose

Permitir que a extração de exames em PDF continue executando de forma resiliente em segundo plano com barra de progresso no menu de notificações do Android, ação de cancelamento e aviso neutro ao finalizar, mantendo privacidade estrita na tela de bloqueio.

## ADDED Requirements

### Requirement: Exam extraction runs resiliently in background with a progress notification

O sistema SHALL executar a extração de um PDF importado (no caminho local ou no fallback privado na nuvem `opt-in`) em tarefa de segundo plano promovida a serviço em primeiro plano (`dataSync`), mantendo uma notificação contínua com barra de progresso atualizada por página/bloco e ação direta para cancelar.

#### Scenario: User leaves the app while an exam is being processed
- **WHEN** a pessoa inicia a extração de um PDF e minimiza o aplicativo ou bloqueia a tela antes da conclusão
- **THEN** a extração continua em segundo plano com notificação ativa exibindo barra de progresso proporcional às páginas e blocos processados, sem interromper a leitura

#### Scenario: User reopens the app during an active background extraction
- **WHEN** a pessoa reabre o aplicativo (pelo ícone ou tocando na notificação em andamento) enquanto a extração ainda está ativa
- **THEN** o aplicativo restaura a visualização de andamento sincronizada com o progresso real em segundo plano, sem iniciar uma segunda extração duplicada

#### Scenario: User cancels extraction from the notification drawer
- **WHEN** a pessoa aciona o botão de cancelar diretamente na notificação de progresso
- **THEN** a tarefa em segundo plano, o processo filho `:inference` ou a chamada HTTPS em andamento são encerrados cooperativamente, o registro incompleto é limpo com segurança e a notificação contínua é removida

### Requirement: Completion and failure notifications are strictly privacy-preserving and non-diagnostic

Ao concluir ou interromper com falha uma extração em segundo plano, o sistema SHALL encerrar a notificação de progresso e emitir uma notificação final com visibilidade privada na tela de bloqueio, usando redação neutra sem dados clínicos, sem PII, sem nome do arquivo e sem linguagem diagnóstica. Tocar na notificação de conclusão SHALL abrir diretamente os resultados daquele exame.

#### Scenario: Background extraction finishes successfully
- **WHEN** a extração de um exame em segundo plano termina e salva o resultado cifrado no armazenamento local
- **THEN** a notificação de progresso é substituída por uma notificação de conclusão neutra e, ao tocá-la, o aplicativo abre diretamente a tela de resultados correspondente ao identificador opaco do exame

#### Scenario: Background extraction fails due to operational error
- **WHEN** a extração em segundo plano falha por erro de arquivo, espaço, conectividade ou limite operacional
- **THEN** a notificação de progresso é encerrada e substituída por uma notificação de aviso operacional sem expor detalhes clínicos, direcionando o usuário de volta ao aplicativo ao ser tocada

#### Scenario: Lock screen privacy redaction
- **WHEN** qualquer notificação de progresso ou conclusão é exibida com o aparelho bloqueado
- **THEN** a notificação utiliza visibilidade privada e nunca inclui nome do paciente, nome do PDF, analitos, valores laboratoriais, faixas de referência ou alertas diagnósticos

### Requirement: Notification permission is requested gracefully without blocking background work

No Android 13 ou superior, o sistema SHALL solicitar a permissão de notificações (`POST_NOTIFICATIONS`) de forma clara antes ou ao iniciar operações em segundo plano. Caso o usuário negue ou revogue a permissão, o sistema SHALL continuar permitindo a extração de exames e o download de pesos normalmente sem falhas nem bloqueios.

#### Scenario: User denies notification permission on Android 13+
- **WHEN** o usuário nega a permissão `POST_NOTIFICATIONS` no sistema operacional e inicia uma extração de exame ou download do modelo local
- **THEN** a tarefa em segundo plano é executada normalmente, o acompanhamento permanece visível dentro do aplicativo e nenhuma exceção ou bloqueio impede a conclusão da operação
