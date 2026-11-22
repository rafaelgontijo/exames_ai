## Purpose

Permitir fotografar uma página de exame e encaminhá-la ao fluxo privado existente, sem criar cópias clínicas em armazenamento público ou temporários persistentes em claro.

## ADDED Requirements

### Requirement: Camera is a voluntary capture option
Enviar exame SHALL oferecer captura pela câmera traseira e seleção de PDF/imagem. A permissão de câmera SHALL ser solicitada somente ao escolher fotografar. Ausência de câmera ou permissão negada/revogada SHALL preservar o seletor e apresentar erro recuperável.

#### Scenario: Permission is denied
- **WHEN** o usuário nega a câmera
- **THEN** nenhum documento é criado e a importação pelo seletor continua disponível

### Requirement: Capture is reviewed before protected publication
Cada fotografia SHALL representar um novo documento JPEG de uma página, com prévia orientada, ações usar/refazer/cancelar e persistência apenas após usar. A captura SHALL respeitar 20 MiB e o limite de raster do pipeline, sem gravar imagem em claro, galeria, logs ou estado restaurável.

#### Scenario: Retake or cancel
- **WHEN** o usuário refaz ou cancela a fotografia
- **THEN** o candidato anterior é descartado e não deixa documento publicado ou arquivo clínico em claro

#### Scenario: Capture exceeds limits or fails
- **WHEN** a câmera retorna conteúdo inválido, excessivo ou orientação inconsistente
- **THEN** o app rejeita a captura sem agendar processamento e permite refazer ou usar o seletor

### Requirement: Confirmed capture uses the existing processing contract
Após confirmação, o app SHALL validar bytes, calcular hash e concluir a cópia privada cifrada antes de criar a execução no modo escolhido. Fonte, prévia, OCR e regiões SHALL corresponder à mesma imagem orientada persistida. Não SHALL fabricar referência, confirmação humana ou confiança de extração.

#### Scenario: Reopen after camera capture
- **WHEN** uma captura confirmada é processada e o app reinicia
- **THEN** o histórico reabre o original privado e a evidência alinhada sem câmera, arquivo externo ou nova fotografia

### Requirement: Late camera callbacks cannot publish discarded data
Cancelar, sair da captura, perder permissão ou encerrar sua sessão SHALL impedir publicação por callbacks tardios e liberar recursos. Morte do processo antes da confirmação SHALL descartar a prévia; interrupção após confirmação SHALL usar a recuperação do importador protegido.

#### Scenario: Callback arrives after navigation
- **WHEN** a captura retorna depois de o usuário sair da tela
- **THEN** não cria documento, execução ou prévia na sessão seguinte
