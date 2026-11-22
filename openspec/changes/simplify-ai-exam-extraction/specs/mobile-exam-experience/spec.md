# Spec Delta

## Purpose

Levar a pessoa do seletor de PDF aos resultados extraídos com IA, com uma tela inicial simples e conferência da fonte no próprio resultado.

## ADDED Requirements

### Requirement: One flow starts with a clear PDF action

A tela inicial SHALL apresentar uma única ação principal, `Selecionar PDF`. A seleção válida SHALL iniciar a extração com IA local. O andamento SHALL indicar a página ou etapa atual e oferecer cancelamento. A rota de usuário SHALL conter apenas seleção, processamento e resultados.

#### Scenario: Select and read a digital PDF
- **WHEN** a pessoa abre o app e escolhe um PDF digital
- **THEN** o app inicia a extração, mostra o andamento e abre os resultados sem pedir que a pessoa escolha um modo ou configure uma bancada

#### Scenario: Choose a file outside the supported format
- **WHEN** o arquivo não é PDF digital legível, excede os limites ou exige senha
- **THEN** o app explica a limitação e permite selecionar outro arquivo

### Requirement: Results show a simple list and expandable evidence

A tela de resultados SHALL agrupar itens por página em ordem documental e exibir nome, resultado, unidade e referência verificados. A página de origem SHALL ser acessível por `Ver no laudo`. Falhas de página e campos a conferir SHALL aparecer sem ocultar resultados verificados. O resultado SHALL oferecer `Selecionar outro PDF` e descarte da extração atual.

#### Scenario: Inspect an uncertain reference
- **WHEN** a pessoa toca em `Ver no laudo` em um resultado com referência ambígua
- **THEN** o app abre a página com a região disponível e mantém a indicação de que o vínculo requer conferência

### Requirement: Ancillary features are absent from the shipped app

O app distribuído SHALL apresentar apenas o fluxo de extração. SHALL retirar abas, acesso a histórico, corpus, comparação de modos, edição persistida, avaliação clínica ou referencial, câmera, áudio e compartilhamento. A atualização SHALL preservar dados locais preexistentes.

#### Scenario: Update the installed app
- **WHEN** a instalação anterior com dados locais é atualizada
- **THEN** o app abre no fluxo simplificado. Os dados legados permanecem cifrados e a atualização não executa exclusão automática

#### Scenario: Inspect app permissions and entry points
- **WHEN** a distribuição é auditada
- **THEN** rotas, permissões e componentes exclusivos da câmera, áudio e compartilhamento não estão ativos nem acessíveis
