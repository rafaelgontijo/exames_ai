# on-demand-model-delivery Specification

## Purpose

Permitir a distribuição do aplicativo com APK leve, transferindo o download dos pesos locais do Gemma 4 E2B (~2,58 GB) para execução sob demanda em background com verificação criptográfica atômica, controle pelo usuário e gestão segura de armazenamento privado.

## Requirements

### Requirement: Local multimodal weights are downloaded on demand outside the APK

A distribuição padrão do aplicativo SHALL NOT embarcar o arquivo de pesos `gemma-4-E2B-it.litertlm` dentro do APK. Em dispositivos elegíveis para inferência local, o sistema SHALL baixar os pesos fixados em background sobre HTTPS para o diretório privado sem backup (`noBackupFilesDir`), gravando primeiro em arquivo temporário e promovendo o artefato de forma atômica somente após validação completa de tamanho exato e hash SHA-256.

#### Scenario: Successful verified background download on capable device
- **WHEN** o download dos pesos locais é iniciado em um aparelho com hardware compatível e espaço livre suficiente
- **THEN** o sistema baixa o artefato via HTTPS em background, valida o tamanho exato em bytes e o hash SHA-256 fixado no manifesto do modelo, move o arquivo de forma atômica para o diretório privado sem backup e marca o provedor local como pronto para uso offline

#### Scenario: Integrity check fails after download
- **WHEN** o arquivo baixado diverge do tamanho esperado ou do hash SHA-256 fixado
- **THEN** o sistema descarta imediatamente o arquivo temporário, não promove pesos corrompidos, reporta código operacional seguro de integridade sem gravar dados clínicos e mantém a opção de tentar novamente ou usar fallback em nuvem mediante consentimento

#### Scenario: Insufficient free disk space before download
- **WHEN** o aparelho não possui espaço livre suficiente para o tamanho do modelo somado à margem de segurança operacional
- **THEN** o sistema bloqueia o download antes de alocar o arquivo, exibe aviso claro de espaço insuficiente e preserva o fallback em nuvem opt-in como alternativa

### Requirement: User controls background download lifecycle and local model storage

O sistema SHALL permitir ao usuário acompanhar o andamento do download dos pesos locais, cancelar ou retomar transferências interrompidas sem corromper o diretório privado e remover os pesos baixados localmente para liberar espaço quando desejar. Preferências operacionais do download (como restrição a Wi-Fi) SHALL ser armazenadas apenas em armazenamento de preferências sem PII.

#### Scenario: Download cancelled or interrupted by network loss
- **WHEN** o usuário cancela o download em andamento ou a conectividade cai durante a transferência
- **THEN** o estado é tratado de forma segura sem deixar artefatos promovidos inválidos e o usuário pode retomar ou reiniciar o download posteriormente

#### Scenario: User removes local model weights to reclaim storage
- **WHEN** o usuário aciona a remoção dos pesos locais nas configurações do aplicativo
- **THEN** o sistema exclui o arquivo de pesos do diretório privado sem afetar o histórico cifrado de exames e atualiza imediatamente o motor de decisão para refletir que o modelo local está ausente
