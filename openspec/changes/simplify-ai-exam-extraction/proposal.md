# Proposal

## Why

No ensaio autorizado no S25, o app abriu as seis páginas do laudo real e publicou zero resultados. A extração dependia de um layout que o parser não reconheceu. A interface também apresenta controles da bancada que dificultam chegar ao resultado.

## What Changes

- **BREAKING** Concentrar o app em selecionar um PDF digital, extrair os resultados com IA local e conferir a saída ao lado da evidência no laudo. A primeira entrega usa o formato do PDF autorizado.
- Permitir que a IA proponha resultados que o parser antigo não reconheceu. Publicar campos somente após verificar texto, página, posição e vínculo com o resultado na fonte. Marcar associações duvidosas para conferência e sinalizar páginas com falha.
- Exibir o nome e o resultado como impressos, unidade e referências quando vinculadas ao mesmo exame. Preservar resultados calculados ou qualitativos com seus próprios rótulos. Omitir identificação do paciente e conteúdo administrativo da saída.
- **BREAKING** Retirar da distribuição do app a bancada, histórico navegável, comparação de modos, classificação referencial, correções persistidas, importação de corpus, câmera, leitura em voz alta e compartilhamento. Permitir descartar a extração atual. Preservar dados locais legados durante a atualização.
- Usar o laudo real autorizado como única fonte para desenho do extrator e aceite fim a fim. Conferir a saída página a página no S25 offline. Manter o PDF e o gabarito fora do repositório, APKs e relatórios.

## Capabilities

### New Capabilities

- `local-structured-inference`: extração local com IA, validação de evidência e aceite com laudo real.
- `mobile-exam-experience`: fluxo único do seletor de PDF à conferência do resultado.

### Modified Capabilities

Nenhuma publicada em `openspec/specs`. Os mesmos caminhos aparecem em changes anteriores ainda sem sincronização. Para o escopo de produto desta entrega, esta proposta substitui os requisitos conflitantes dessas changes, inclusive o aceite pendente do protótipo anterior. A conclusão histórica dos ensaios continua registrada nos artefatos existentes.

## Impact

- Navegação e telas Compose, importação por SAF, PDFium, persistência privada, orquestração e validação da inferência.
- Manifesto, dependências incluídas no app, documentação e procedimento de aceite local.
- LiteRT-LM e modelo empacotado continuam offline. A configuração da nova extração depende de confirmação no S25.
