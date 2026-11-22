## Purpose

Oferecer uma experiência Android coerente com as quatro telas do protótipo, mantendo os resultados referenciais, as ambiguidades e a bancada de revisão acessíveis e fiéis aos dados persistidos.

## ADDED Requirements

### Requirement: Native screens follow the approved visual identity
O aplicativo SHALL apresentar Início, Enviar exame, Resultados e Ajuda de revisão em interface nativa com identidade “Meu Exame”, cartões, hierarquia e navegação inferior equivalentes ao protótipo, adaptadas a insets e tamanho de tela. SHALL preservar applicationId e dados existentes.

#### Scenario: Navigate without losing a document
- **WHEN** o usuário abre um documento pelo Início, consulta Resultados, visita Ajuda e retorna
- **THEN** a seleção e as ações disponíveis correspondem ao mesmo documento/execução, sem transportar conteúdo clínico nas rotas

### Requirement: Home and history represent stored state
O Início SHALL mostrar documentos reais com formato, data de importação e estado da última extração automática identificada por modo. Histórico vazio, indisponível e processamento incompleto SHALL ser distintos. Exemplos e datas do HTML SHALL NOT aparecer como dados do usuário.

#### Scenario: No imported documents
- **WHEN** não existem documentos salvos
- **THEN** mostra estado vazio e ação para importar, sem exames ou contagens fictícios

#### Scenario: Latest extraction is incomplete
- **WHEN** a última extração automática está em fila, parcial, falhou ou foi cancelada
- **THEN** o cartão identifica esse estado, sem usar uma execução antiga para alegar conclusão

### Requirement: Result cards preserve assessment semantics
Cartões e resumo SHALL distinguir dentro, abaixo e acima da referência informada, NEEDS_REVIEW e NOT_CLASSIFIABLE, com motivos e completude visíveis. SHALL identificar automático/corrigido/reavaliado, modo e participação real/fallback da IA. SHALL NOT transformar comparação referencial em diagnóstico ou garantia de normalidade.

#### Scenario: Ambiguous and unsupported content
- **WHEN** há ambiguidades, referência ausente, conteúdo não suportado ou página incompleta
- **THEN** mostra revisão/limitação explicitamente e não exibe “Tudo normal”, “Está bom” ou conclusão equivalente

#### Scenario: Review a multi-page report with direct and estimated measurements
- **WHEN** o usuário importa um laudo sintético com glicose medida em um bloco, glicemia média estimada em outro e referências distintas por contexto
- **THEN** cada medida apresentada remete ao bloco e à página que a sustentam. A estimativa não é contada como glicose medida. Uma associação ambígua exige revisão, inclusive quando o valor e a unidade parecem compatíveis. A leitura e a prévia de compartilhamento refletem o mesmo resumo da execução selecionada.

### Requirement: Reviewer tools remain reachable
A nova experiência SHALL manter acesso a fonte destacada, correção de extração, revisão de gabarito, versões, reavaliação/reexecução, corpus, relatórios A/B, progresso/cancelamento e exclusão confirmada. A simplificação visual SHALL NOT substituir ou alterar métricas originais.

#### Scenario: Investigate a card
- **WHEN** o usuário seleciona um campo ou abre os detalhes de validação
- **THEN** alcança a fonte e as ações de revisão existentes para a execução escolhida

### Requirement: Help describes review and local privacy
Ajuda SHALL explicar leitura referencial, ambiguidade, conferência da fonte, operação local e compartilhamento voluntário. SHALL NOT recomendar urgência, prazos de consulta, especialidades, doenças ou UBS próximas. O app SHALL manter os cinco analitos suportados, sem referências clínicas fixas do HTML.

#### Scenario: Open the adapted orientation screen
- **WHEN** o usuário abre Ajuda a partir dos resultados
- **THEN** encontra instruções de revisão documental, sem triagem, localização ou recomendações clínicas personalizadas

### Requirement: Visual fidelity remains accessible and offline
A interface SHALL funcionar com TalkBack, fonte a 200%, temas claro/escuro, alvos de pelo menos 48 dp e contraste mínimo 4,5:1 para texto comum e 3:1 para controles. SHALL usar recursos locais e texto além de cores para estados, sem corte de valores, ações ou mensagens críticas.

#### Scenario: Review with enlarged font and TalkBack
- **WHEN** o usuário navega com fonte 200% e leitor de tela
- **THEN** títulos, estados, valores/unidades e ações têm ordem e rótulos compreensíveis, com rolagem e sem controles inacessíveis

### Requirement: Import stays local without a clinical approval gate
A tela SHALL explicar o processamento neste aparelho, sem sugerir upload ou consentimento genérico para exportar. Após a escolha/confirmação da fonte, SHALL manter a avaliação automática existente, sem exigir confirmação prévia de campos ou aceite clínico. Confirmar fotografia SHALL significar somente usar aquela imagem.

#### Scenario: Start processing a selected source
- **WHEN** o usuário confirma a foto ou seleciona PDF/imagem válidos
- **THEN** o pipeline inicia no modo escolhido sem confirmação clínica adicional, mantendo revisão de ambiguidades após a extração
