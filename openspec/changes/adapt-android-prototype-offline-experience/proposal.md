# Proposal

## Why

A bancada Android já importa, processa e revisa exames offline, mas sua interface funcional não acompanha as quatro telas “Meu Exame” fornecidas pelo usuário. Adaptar essa experiência em Compose e acrescentar câmera, leitura em voz alta e compartilhamento local tornará os fluxos mais acessíveis sem substituir a avaliação determinística ou esconder suas limitações.

## What Changes

- Adotar a identidade visual do HTML: fundo azul claro, cartões brancos, ações azuis, tipografia e hierarquia equivalentes, ícones e navegação inferior; exibir a marca “Meu Exame” sem mudar applicationId ou dados existentes.
- Criar Início com documentos reais, Enviar exame com câmera/SAF, resultados com resumo referencial e cartões, e Ajuda de revisão em lugar da orientação clínica do protótipo. Manter acesso a fontes, correções, gabaritos, modos, fallback, relatórios e exclusão.
- Capturar uma página por vez pela câmera interna, permitir conferir/refazer e importar diretamente para o armazenamento protegido e pipeline existentes.
- Ler o resumo referencial em pt-BR somente com voz local instalada e escolhida explicitamente; oferecer parar e tratar indisponibilidade sem baixar voz ou usar síntese em rede.
- Compartilhar, após prévia e confirmação específica, um resumo textual da execução selecionada por URI temporária somente leitura, com persistência interna criptografada, expiração e revogação.
- Preservar cinco analitos, referências do próprio documento, estados NEEDS_REVIEW/NOT_CLASSIFIABLE, privacidade e modo sem IA padrão. Não reproduzir diagnósticos, riscos de doenças, urgência, indicação de especialidades, localização de UBS, exemplos clínicos fixos ou promessa “Tudo normal”.
- Validar navegação e fidelidade visual, TalkBack, fonte 200%, modos claro/escuro, primeira utilização offline e privacidade no S25 e emulador API 24.
- Usar o PDF pessoal em `examples/` como referência local de estrutura para criar um laudo inteiramente sintético de seis páginas. Usá-lo no aceite de importação, revisão por página, leitura e compartilhamento, com atenção à diferença entre uma medida direta e uma estimativa citada no laudo.

## Capabilities

### New Capabilities

- `mobile-exam-experience`: identidade visual, navegação, apresentação de dados reais e ajuda de revisão com continuidade da bancada técnica.
- `private-camera-capture`: captura, conferência e importação protegida de fotografias locais.
- `offline-result-audio`: leitura voluntária do resumo por voz local, disponibilidade e ciclo de vida seguro.
- `controlled-result-sharing`: prévia, exportação textual versionada, concessão temporária e limpeza recuperável.

### Modified Capabilities

Nenhuma especificação principal existente será modificada. `openspec list --specs` não retorna capacidades publicadas; os contratos da change concluída `add-android-biochemistry-validation`, ainda não sincronizada/arquivada, são a baseline a preservar, especialmente `validation-review`, `document-processing`, `protected-local-history` e `local-structured-inference`. Esta change acrescenta experiência e adaptadores, sem redefinir esses contratos.

## Impact

- `:app`: tema, shell/navegação, composição Hilt, permissões de câmera e visibilidade do serviço TTS, provider de compartilhamento privado.
- `:feature-import`, `:feature-history`, `:feature-results`: novos layouts/estados e acessos preservados aos casos de uso atuais; adaptadores de câmera/TTS isolados de Compose.
- `:data-documents` e `:data-local`: entrada de bytes capturados validada, reutilização do importador transacional, artefatos de exportação cifrados e integração com tombstone/outbox.
- `:domain`: projeção determinística de resumo textual compartilhada pela tela/áudio/exportação, com testes de limites e fallback; sem Android SDK, narrativas de LLM ou novas regras clínicas.
- Dependência Android CameraX fixada e auditada; TTS/Sharesheet nativos. Sem WebView, Internet, geolocalização, telemetria, armazenamento público ou serviços de nuvem. minSdk 24 permanece.
- HTML de referência preservado e fora do Git, conforme instrução anterior; proposta registra seu hash e parâmetros visuais, sem incorporá-lo ou seus textos clínicos ao app.
