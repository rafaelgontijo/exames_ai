# Spec Delta

## Purpose

Define a experiência mobile unificada do aplicativo Meu Exame em seis telas alinhadas ao projeto Stitch `Meu Exame — MVP`, cobrindo navegação principal, importação de PDF, acompanhamento da leitura offline, conferência de resultados com filtros, visualização de evidência no laudo e ajuda documental, sem a variante legada de bancada.

## ADDED Requirements

### Requirement: Home screen presents brand entry, guidance, recent exams, and bottom navigation

A tela inicial SHALL exibir o cabeçalho da marca com atalho para ajuda, uma saudação acolhedora, um cartão principal para iniciar a importação de PDF offline, um guia visual em três etapas, a lista recente de exames salvos localmente e uma barra de navegação inferior fixa com as abas `Início`, `Exames` e `Ajuda`.

#### Scenario: Open app with no saved exams
- **WHEN** a pessoa abre o aplicativo sem nenhum exame salvo no aparelho
- **THEN** a tela inicial exibe o cartão principal de importação, o guia em três etapas, um estado vazio claro na seção de exames recentes e a barra inferior com `Início` selecionado

#### Scenario: Open app with saved exams
- **WHEN** a pessoa abre o aplicativo já havendo exames salvos localmente
- **THEN** a tela inicial lista os exames mais recentes com data de importação e resumo de conferência, permitindo abrir qualquer exame diretamente na tela de resultados

### Requirement: Dedicated import screen guides digital PDF selection and offline privacy

A tela de envio de exame SHALL apresentar a ação primária `Selecionar PDF digital`, a ação secundária `Abrir histórico local`, um bloco com dicas para uma leitura fiel e um aviso de privacidade reforçando o processamento 100% offline com armazenamento privado e cifrado no aparelho.

#### Scenario: Start PDF selection from the import screen
- **WHEN** a pessoa toca em `Selecionar PDF digital` e escolhe um arquivo PDF válido pelo seletor do sistema
- **THEN** o aplicativo inicia a extração local e navega para a tela de progresso da leitura offline

#### Scenario: Select an unsupported or unreadable file
- **WHEN** a pessoa seleciona um arquivo que não é PDF digital legível, está vazio, excede o limite suportado ou exige senha
- **THEN** o aplicativo exibe uma explicação clara da limitação sem sair do fluxo e permite escolher outro arquivo

### Requirement: Dedicated progress screen reports page-by-page offline extraction and cancellation

A tela de processamento SHALL exibir selo de execução offline no aparelho, cartão de progresso com percentual e contador de página/bloco atual, lista de andamento por página (concluídas com quantidade de itens verificados, página em leitura e páginas aguardando), aviso de privacidade e botão `Cancelar leitura`.

#### Scenario: Track multi-page PDF extraction
- **WHEN** um PDF de múltiplas páginas está sendo processado pela IA local
- **THEN** a tela atualiza o percentual, o bloco atual e o estado de cada página concluída em tempo real até abrir automaticamente a tela de resultados ao concluir

#### Scenario: Cancel extraction in progress
- **WHEN** a pessoa toca em `Cancelar leitura` durante o processamento
- **THEN** o aplicativo interrompe a inferência local, descarta a cópia temporária daquela leitura e retorna ao fluxo de origem com aviso de cancelamento

### Requirement: Results screen displays summary banner, status filters, and documentary cards

A tela de resultados SHALL exibir um banner de resumo destacando quantos itens pedem conferência com atalho `Ver no laudo`, a seção `Resultados extraídos` com metadados de páginas processadas, uma barra horizontal de filtros acessíveis por estado (`Todos`, `Revisar`, `Na referência` e `Sem faixa` quando presente) e cartões em ordem documental contendo nome, valor, unidade, referência bruta preservada, página, chip de estado e ação `Ver no laudo`, além de acesso à ajuda e exclusão do exame.

#### Scenario: Filter extracted measurements by status
- **WHEN** a pessoa seleciona o chip `Revisar` na tela de resultados
- **THEN** a lista exibe apenas os itens classificados para revisão preservando a ordem documental e mantendo visíveis os avisos de página aplicáveis

#### Scenario: Inspect partial extraction with page issues
- **WHEN** uma ou mais páginas do PDF apresentam falhas ou avisos de cobertura
- **THEN** a tela exibe os avisos correspondentes sem ocultar os resultados verificados das demais páginas

### Requirement: Dedicated evidence screen highlights original PDF regions with field toggles

A tela `Ver no laudo` SHALL substituir o diálogo simples por uma visualização dedicada contendo barra superior com `Página X de Y`, cartão de contexto da medição selecionada com o texto bruto da referência preservado, alternância `Página inteira` e `Recorte`, seletores de destaque por campo (`Resultado` e `Referência`), prévia proporcional da página do PDF com sobreposições translúcidas nas regiões vinculadas e rodapé não diagnóstico com botão `Voltar aos resultados`.

#### Scenario: Switch between full page, cropped view, and field highlights
- **WHEN** a pessoa alterna entre `Página inteira` e `Recorte` ou ativa/desativa os filtros `Resultado` e `Referência`
- **THEN** a prévia ajusta o recorte e os contornos destacados usando apenas as coordenadas originais do PDF, sem alterar o texto subjacente nem inventar posições ausentes

#### Scenario: Open evidence for an item with uncertain reference linkage
- **WHEN** a pessoa abre `Ver no laudo` para uma medição com vínculo de referência marcado para conferência
- **THEN** a tela mantém explícita a marcação `Revisar` e exibe o texto bruto e as regiões disponíveis para conferência visual

### Requirement: Help and review screen communicates markings, privacy, and non-diagnostic boundaries

A tela `Ajuda e revisão` SHALL explicar como conferir os resultados no laudo original, apresentar a legenda dos estados (`Na referência`, `Revisar` e `Sem faixa`), informar o armazenamento privado e cifrado no aparelho e exibir de forma destacada que o aplicativo não realiza diagnósticos, recomendações clínicas, triagem de urgência ou indicação de serviços de saúde.

#### Scenario: Inspect non-diagnostic boundary and legend
- **WHEN** a pessoa acessa a aba `Ajuda` ou o atalho de ajuda na tela de resultados
- **THEN** a tela exibe a legenda completa das marcações e o aviso de limite não diagnóstico sem recomendar especialidades, prazos ou serviços clínicos

### Requirement: Application ships as a single unified offline build with accessible design tokens

O aplicativo SHALL ser distribuído como uma única base unificada sem a variante técnica de bancada legada, sem permissões ou bibliotecas de rede, sem câmera, áudio ou compartilhamento no escopo atual, preservando `FLAG_SECURE`, tema claro e escuro, fonte local offline, contraste mínimo de 4,5:1 em texto e 3:1 em controles, alvos de toque de pelo menos 48 dp, suporte à escala de fonte até 200% e ausência de dados clínicos em rotas ou estado restaurável.

#### Scenario: Audit shipped distribution and accessibility
- **WHEN** a build padrão do aplicativo é auditada quanto a manifesto, acessibilidade e navegação
- **THEN** não existem permissões de rede ou componentes exclusivos da bancada legada, todas as telas do fluxo atendem aos critérios de contraste, toque e escala de fonte de 200%, e nenhuma rota ou estado salvo armazena texto clínico
