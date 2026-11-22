## Purpose

Permitir ouvir voluntariamente o resumo referencial da execução selecionada com síntese local, preservando ambiguidades, identidade da saída e privacidade durante todo o ciclo de vida.

## ADDED Requirements

### Requirement: Audio uses installed local Portuguese speech
O app SHALL oferecer Ouvir/Parar somente após detectar e selecionar uma voz pt-BR instalada que não exija rede. SHALL NOT baixar voz, usar fallback de rede ou gerar arquivo de áudio persistente. Sem voz local elegível, SHALL informar indisponibilidade e manter os resultados textuais utilizáveis.

#### Scenario: Only a network voice is available
- **WHEN** o dispositivo oferece apenas voz dependente de conexão ou idioma indisponível
- **THEN** o app não envia texto para síntese, não inicia download e informa que a leitura offline está indisponível

#### Scenario: First offline use on the reference device
- **WHEN** o S25 possui voz local pt-BR e o usuário escolhe Ouvir em instalação limpa em modo avião, com Wi-Fi desligado
- **THEN** ocorre reprodução audível real, com ação Parar; stub ou indisponibilidade exclusiva não satisfazem esse aceite

### Requirement: Speech mirrors a deterministic review summary
A leitura SHALL reproduzir campos e estados da execução explicitamente selecionada, com base, modo, completude, unidades e referências da fonte. SHALL verbalizar ambiguidades/abstenções, sem interpretar doenças, recomendar conduta ou acessar raciocínio do modelo. Resumos extensos SHALL ser segmentados sem omitir conteúdo ou falhar explicitamente antes de falar.

#### Scenario: Ambiguous result in a corrected execution
- **WHEN** a saída escolhida é corrigida e ainda contém NEEDS_REVIEW
- **THEN** o áudio identifica a saída como corrigida e a necessidade de revisão, sem anunciar normalidade ou tratar correção como nova inferência

### Requirement: Speech is scoped to the active screen and execution
A reprodução SHALL parar ao comando do usuário, saída da tela, segundo plano, perda de foco de áudio, troca/alteração da execução ou exclusão. Eventos atrasados SHALL NOT reiniciar fala, falar conteúdo excluído ou alterar o estado de uma nova sessão. O app SHALL NOT restaurar texto clínico/fila após morte do processo.

#### Scenario: Delete while speaking
- **WHEN** o documento é excluído durante a reprodução
- **THEN** a fala é interrompida e callbacks posteriores não a retomam
