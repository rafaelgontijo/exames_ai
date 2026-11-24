# on-demand-model-delivery Delta Specification

## MODIFIED Requirements

### Requirement: User controls background download lifecycle and local model storage

O sistema SHALL executar o download dos pesos locais em segundo plano como serviço em primeiro plano (`dataSync`) com notificação contínua exibindo barra de progresso e ação de cancelamento no menu de notificações do Android, emitindo notificação neutra ao concluir ou falhar a verificação SHA-256. O usuário SHALL poder cancelar, retomar transferências interrompidas e remover os pesos locais sem corromper o histórico cifrado, salvando preferências operacionais (como Wi-Fi) sem PII.

#### Scenario: Active download displays progress bar in Android notification drawer
- **WHEN** o usuário inicia o download dos pesos locais (~2,58 GB) e sai do aplicativo ou bloqueia a tela
- **THEN** o download prossegue em segundo plano com notificação contínua exibindo barra de progresso percentual e tamanho transferido, acompanhada da ação de cancelar na notificação

#### Scenario: Download completes and passes SHA-256 verification
- **WHEN** a transferência dos pesos locais termina e a validação criptográfica SHA-256 é aprovada com promoção atômica
- **THEN** a notificação de progresso é removida e o sistema exibe uma notificação final informando que o modelo local está pronto para uso offline, abrindo o aplicativo ao ser tocada

#### Scenario: Download cancelled or interrupted by network loss
- **WHEN** o usuário cancela o download (pelo aplicativo ou pela ação na notificação) ou a conectividade cai durante a transferência
- **THEN** a notificação de progresso é removida, o arquivo temporário é tratado com segurança sem promover artefatos inválidos e, em falha de rede/espaço/integridade, uma notificação neutra informa que o download não foi concluído

#### Scenario: User removes local model weights to reclaim storage
- **WHEN** o usuário aciona a remoção dos pesos locais nas configurações do aplicativo
- **THEN** o sistema exclui o arquivo de pesos do diretório privado sem afetar o histórico cifrado de exames e atualiza imediatamente o motor de decisão para refletir que o modelo local está ausente
