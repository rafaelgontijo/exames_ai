## Purpose

Permitir uma entrega deliberada e temporária de resumo referencial ao aplicativo escolhido pelo usuário, com rastreabilidade da execução e sem exportação automática ou arquivos clínicos internos em claro.

## ADDED Requirements

### Requirement: Sharing requires an exact preview and explicit action
O app SHALL apresentar prévia do resumo textual da execução escolhida e solicitar confirmação específica antes do seletor de destino. SHALL explicar que o destinatário poderá guardar ou transmitir sua cópia. Consentimento de importação SHALL NOT autorizar compartilhamento, e cancelar SHALL NOT conceder acesso.

#### Scenario: Cancel preview
- **WHEN** o usuário fecha a prévia ou cancela compartilhar
- **THEN** nenhum destinatário recebe conteúdo ou permissão

### Requirement: Export is source-bound and unambiguous
O resumo SHALL identificar saída automática/corrigida/reavaliada, modo, estado parcial, campos, motivos de revisão e referências do documento. SHALL NOT inventar dados, incluir raciocínio, identificadores de paciente, URIs originais, diagnósticos ou orientação clínica. O limite de 64 KiB UTF-8 SHALL produzir erro explícito, sem truncamento silencioso.

#### Scenario: Export a partial run
- **WHEN** a execução escolhida possui página com falha ou campos não classificáveis
- **THEN** a prévia e o conteúdo entregue preservam essas limitações, sem conclusão de normalidade

### Requirement: Recipient access is narrow and expires
A entrega SHALL usar URI opaca específica, somente leitura, válida por no máximo dez minutos ou até revogação/exclusão. O app SHALL persistir conteúdo somente cifrado, sem arquivo em claro em cache/galeria ou texto clínico nos extras do seletor. Geração e entrega locais SHALL funcionar sem rede; comportamento do destinatário fica fora desse controle.

#### Scenario: Read an expired or unauthorized URI
- **WHEN** uma URI expirou, foi revogada, não possui concessão ou é aberta para escrita
- **THEN** o acesso é negado, sem revelar conteúdo ou caminho privado

### Requirement: Sharing integrates with deletion and recovery
Excluir o documento SHALL revogar acessos, bloquear novas leituras e interromper envio ainda controlado pelo app, com limpeza recuperável de exports cifrados e concessões. Reinício SHALL reconciliar expiração/revogação antes de servir conteúdo. O app SHALL NOT prometer apagar bytes já entregues ao sistema ou destinatário.

#### Scenario: Deletion races with recipient read
- **WHEN** a exclusão ocorre antes da abertura ou durante a transmissão do resumo
- **THEN** novas aberturas são negadas e a transmissão controlada pelo app é encerrada; bytes já entregues ao sistema ou destinatário são explicitamente fora da garantia de exclusão

#### Scenario: Restart with an unused pending share
- **WHEN** o processo reinicia com uma exportação pendente
- **THEN** valida documento, prazo e concessão antes de qualquer leitura e limpa exports inválidos sem restaurar texto clínico na UI

### Requirement: Summary does not blindly copy ambiguous free text
A projeção compartilhável SHALL limitar-se aos campos referenciais estruturados. Texto livre ambíguo ou não suportado SHALL ser substituído por marcador explícito de revisão e indicação da fonte privada, sem copiar cabeçalhos/identidade ou inventar dados. A prévia SHALL mostrar exatamente essas omissões sinalizadas.

#### Scenario: A malformed field includes an identity marker
- **WHEN** um campo inválido contém texto livre com marcador sintético de identidade
- **THEN** o resumo/áudio/exportação não incluem o marcador e informam que o campo precisa de conferência na fonte
