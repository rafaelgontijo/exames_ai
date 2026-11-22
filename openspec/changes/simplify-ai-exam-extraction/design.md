# Design

## Context

`ProcessBiochemistryPage` executa o parser antes da IA. `ProposalEvidenceValidator` compara os candidatos com as observações desse parser e exige um layout conhecido. `PrivateRunPages` descarta a saída quando esse layout está ausente. Assim, a expressão `PAGE_UNSUPPORTED_LAYOUT` no laudo real não distingue falha da IA de rejeição pelo contrato legado. O novo fluxo precisa medir separadamente preparação, chamada ao modelo, validação e publicação. [Ensaio registrado](../../../docs/VALIDACAO.md#ensaio-suplementar-autorizado-pelo-titular)

O modelo selecionado aceita texto posicionado, com contexto de 2.048 tokens na configuração comprovada e limite de saída de 1.024. A comprovação de isolamento e funcionamento offline no S25 serve de ponto de partida para medir o novo protocolo. [Seleção do modelo](../../../validation/models/selection.json) · [Compatibilidade](../../../docs/LITERT_COMPATIBILITY.md)

## Goals / Non-Goals

- Tornar possível publicar uma linha de resultado com nome livre, desde que seus campos tenham evidência suficiente no PDF.
- Preservar integridade e confidencialidade ao recortar a evidência em requisições menores e combinar seus resultados.
- A primeira entrega delimita a entrada a PDFs digitais com texto posicionado, incluindo a abertura sem senha observada no laudo autorizado. OCR, entradas por imagem e outros perfis de PDF ficam fora deste aceite.

## Decisions

### Extração orientada à fonte

Reutilizar seleção SAF, validação de transporte, abertura privada com PDFium e região de texto. Enviar blocos de texto posicionado com IDs locais à IA. Atualizar o contrato para nomes livres, valores literais, unidade e referências com qualificadores, cada qual com citações próprias. Reaproveitar princípios do validador antigo para reconstrução literal, mantendo o novo validador independente de `ParsedPage`, da lista de cinco analitos e do avaliador.

Delimitar blocos por posição, linhas próximas, colunas e limites visíveis no documento. Verificar que nome e valor pertencem à mesma linha ou bloco e que unidades e referências correspondem à observação. Detectar conflito entre blocos, resultado anterior, valor calculado e texto de referência. Se o vínculo não puder ser demonstrado, abster-se do campo envolvido. Igualdade textual sozinha pode aprovar um número vizinho. Seleção de faixa por idade, sexo, método ou amostra é uma interpretação que o fluxo não executa.

### Processamento por blocos e recuperação

Preservar página, posição e IDs dos trechos na segmentação por blocos contíguos. Ajustar o orçamento efetivo no S25 e dividir blocos grandes pelos limites estruturais, com sobreposição somente para preservar contexto local. Conciliar sobreposições pelos IDs, mantendo ocorrências diferentes com o mesmo nome ou valor. Tratar vínculos entre blocos que ficaram sem contexto como incertos. Registrar blocos não processados e permitir resultado parcial.

Usar inicialmente os pesos e runtime fixados na seleção atual, com nova engine e novo processo por tentativa conforme os controles existentes. Versionar prompt, schema e validação. Manter controle de tempo, concorrência, cancelamento e aposentadoria do processo. Se houver erro de schema ou rejeição de evidência, reter somente campos já validados em tentativas bem-sucedidas e o estado operacional da falha. Reintroduzir o parser como saída automática esconderia a ausência de inferência aprovada.

### Interface e dados

Usar uma tela Compose com três estados. Na entrada, destacar `Selecionar PDF` e uma frase breve sobre a execução no aparelho. No processamento, exibir etapa ou página e `Cancelar`. No resultado, priorizar a lista, com nome acima do valor, unidade ao lado e referência como texto secundário. Usar agrupamento por página e um painel sob demanda para mostrar a evidência. Deixar avisos de cobertura e de conferência próximos dos dados afetados. Usar texto e ícones para indicar estados, com contraste e semântica acessível. Manter o identificador opaco da execução como único dado de navegação.

Reutilizar a cifragem e o ciclo de cancelamento e limpeza para a extração atual. Separar o modelo de leitura novo das tabelas e saídas da bancada. O app precisa de um caminho para descartar a extração atual, inclusive fonte e derivados. Remover o acesso a funções da bancada e os componentes exclusivos dessas funções na variante distribuída. A remoção lógica da interface permite verificar uma atualização sem expurgar dados legados.

### Aceite privado

Usar o PDF autorizado em `examples/` como fonte exclusiva do desenho de cobertura e da aferição fim a fim. Conferir o PDF visualmente no ambiente privado e preparar a lista esperada por página, bloco e campo antes de executar a versão candidata. Manter a lista fora da inferência para que ela não possa ser usada como resposta. O ensaio pode reaproveitar o transporte USB em memória do pacote descartável. Os resultados do laudo não entram em APK, CI ou relatório. Testes unitários de contratos podem usar dados mínimos para testar schema, limites e cancelamento, sem criar laudos artificiais nem usá-los como prova de extração.

## Risks / Trade-offs

- PDF com ordem textual diferente da posição visual. Validar associação com geometria e marcação da página.
- Uma página pode gerar várias tentativas. Medir duração, memória e cobertura no S25 e registrar limites atingidos.
- Um laudo cobre apenas os formatos que ele contém. Limitar a afirmação de qualidade ao documento efetivamente conferido.
- A atualização pode fazer a UI abandonar estados legados. Verificar a abertura no fluxo novo, integridade dos arquivos legados e descarte apenas da execução escolhida.

## Migration Plan

1. Introduzir o contrato de extração e ligar o novo fluxo ao importador privado e ao runtime, sem editar os dados clínicos legados.
2. Substituir a navegação da distribuição e remover componentes exclusivos das funções retiradas. Manter applicationId e compatibilidade com o armazenamento legado. Se houver mudança de schema, usar migração aditiva e testá-la.
3. Validar em pacote separado no S25 offline. Conferir cada página com o laudo privado, registrar apenas o resultado técnico e repetir a validação na versão candidata final.
