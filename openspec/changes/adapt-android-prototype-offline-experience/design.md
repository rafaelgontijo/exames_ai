# Design

## Context

Ver `proposal.md` para motivação e escopo aprovado pelo usuário: visual e novos recursos offline, mantendo a governança atual. Inspeção realizada em `main`, base `a626b5e`:

- `ExamesNavigation.kt` possui rotas import/history/results com IDs opacos e ViewModels da Activity; `ExamesTheme.kt` usa somente esquemas Material padrão.
- Importação SAF passa por `ProtectedDocumentImporter`, que valida e cifra antes de publicar, reconcilia journals e limpa buffers. Não existe entrada pública de bytes capturados, câmera, TTS ou provider de exportação.
- Resultados/histórico já oferecem evidência, revisões append-only, relatórios e exclusão. Esses acessos são baseline, não telas descartáveis do protótipo.
- `Document` contém formato e data de importação; não contém título clínico, data de coleta ou nome de paciente. Não inferir esses campos do HTML.
- Manifest não solicita câmera, Internet ou localização; conteúdo privado fica cifrado, sem backup. App minSdk 24, SDK 36, JDK 21.
- `openspec/specs` não contém capacidades publicadas; a change anterior tem 51/51 tarefas e ainda não foi sincronizada. Não arquivar/sincronizar essa change implicitamente.
- A/B anterior no S25 aceitou 1/8 páginas assistidas, com sete fallbacks; redesenhar a interface não melhora a extração nem autoriza rótulos de sucesso para fallback.

A referência é `App de Exames — MVP.html` (594017 bytes, SHA-256 `7e43f41ac087a1669955693d5d7cf4e8899ca6691fcc721c6180bcaf01202756`). O bundle contém quatro páginas aninhadas com conteúdo estático e exemplos; o JS e as fontes web não serão executados no app. O arquivo permanece inalterado e não rastreado; os parâmetros abaixo tornam o planejamento compreensível sem exigir seu commit.

## Goals / Non-Goals

**Goals:** experiência Compose reconhecível, câmera privada, leitura local acessível e compartilhamento voluntário de um resumo rastreável; preservar casos de uso, dados, estados e instrumentos de validação.

**Non-Goals:** WebView/HTML embarcado, interpretação livre de LLM, novas referências/analitos, triagem, geolocalização/UBS, indicações de especialistas, conta/cloud, sincronização, microfone, reconhecimento de fala, montagem de PDF multipágina por câmera ou mudança do modelo/OCR. Cada captura confirmada cria um JPEG de uma página; PDFs multipágina continuam pelo SAF. O áudio requer uma voz local instalada no sistema; o app não a baixa.

## Decisions

### 1. Traduzir a referência em componentes Compose compartilhados

Criar um módulo pequeno `:core-ui` para tema, tipografia, cartões, chips, botões e scaffolds, consumido pelas três features e app; evita dependência das features no módulo app. Alternativa rejeitada: duplicar tokens ou transformar a UI em WebView, perdendo consistência, acessibilidade nativa e operação offline.

| Referência observada | Aplicação proposta |
| --- | --- |
| Pranchetas 390 × 844 | Referência de proporção; layout responsivo com insets, rolagem e largura útil real do S25 |
| Fundo `#F4F8FD`, superfície branca | Tokens claros; equivalentes escuros explícitos sem desabilitar o tema atual |
| Primária `#1F5FBF`, títulos `#0F3A7A` | Hierarquia azul; contraste medido nos pares reais |
| Texto `#13233A`, secundário `#4A5B72` | Texto principal/secundário, sem cor como único estado |
| Bordas `#D6E6F8`, detalhe `#BBD6F6` | Superfícies decorativas; controles interativos recebem contraste suficiente |
| Figtree 400/500/600/700/800, títulos 20–26 | Fontes Android locais estáticas com licença/pin; tamanhos em sp, sem Google Fonts runtime |
| Cartões 16/20, botões 52/56, margem 20 | dp, alvos mínimos 48 dp, crescimento vertical com fonte 200% |
| Chips azul/amarelo e alerta vermelho | Estados referenciais com texto/ícone; vermelho não significa urgência |

Empacotar Figtree Android compatível com API 24 após verificar origem/licença/hash; não converter silenciosamente WOFF2 em fonte Android nem depender de provider remoto. A versão da biblioteca CameraX e os arquivos de fonte serão fixados e auditados antes de integrar, mantendo minSdk 24.

### 2. Shell, dados reais e acesso à bancada

Início/Exames/Ajuda são os destinos de navegação inferior; Enviar e Resultado pertencem ao fluxo de documentos. “Onde ir” vira Ajuda de revisão. Voltar usa a pilha Android, sem recriar imports, disparar inferência ou falar automaticamente. Recriação restaura somente destinos/IDs opacos; rascunhos, imagens, falas e prévias de exportação não são salvos.

Início observa uma projeção de documento + última execução AUTOMATIC, com modo e estado identificados; saídas corrigidas não substituem silenciosamente o resumo automático. Usar datas de importação e nomes genéricos por formato. Nenhum exame estático do HTML, “urina”, LDL ou hemoglobina entra como dado ou ampliação do parser.

Resultado apresenta primeiro estado/completude, contagens separadas, cartões e ações Ouvir/Compartilhar/Revisar com a fonte. Detalhes da bancada conservam fonte destacada, correção, gabaritos, versões, reextração, modos, corpus e relatórios. Resumo nunca promete “Tudo normal”: usa “dentro da referência informada” e sinaliza abstenções, ausências e páginas incompletas. O texto de ajuda explica operações/revisão, sem repetir o conteúdo clínico da tela Orientação. Enviar informa que o processamento é neste aparelho; o checkbox do HTML não será um aceite clínico nem uma barreira de confirmação prévia à avaliação automática. Confirmar uma foto seleciona a fonte, não confirma seus valores.

### 3. Uma projeção textual determinística, independente do modelo

Criar em `:domain` um snapshot de resumo com document/run ID, base da saída, versão/regra, estado, contagens, campos suportados e avisos de revisão. IDs servem à rastreabilidade interna; não exportar caminhos, URIs, cabeçalhos livres ou campos de identidade do paciente. Campos numéricos/referências utilizam somente dados inequívocos já presentes; texto livre ambíguo ganha marcador explícito e remete à fonte privada, sem tentar detectar PII por LLM. A UI de evidência continua mostrando os brutos originais.

Tela de prévia, áudio e exportação usam a mesma projeção/versionamento, sem recalcular classificação ou aplicar correções implícitas. Exportar saída CORRECTED/REASSESSED exige seleção explícita dessa execução e identifica a base. Limite 64 KiB UTF-8 para o texto completo; excesso falha antes de falar/compartilhar, sem corte. Nenhum novo critério clínico, conversão de unidade ou resumo generativo. Alternativa rejeitada: usar a resposta final ou pensamento do LLM como explicação.

### 4. Câmera própria com candidato em memória e publicação cifrada

Usar CameraX vinculado ao lifecycle, câmera traseira e captura em memória. [A API fornece callback com imagem em memória](https://developer.android.com/media/camera/camerax/take-photo), evitando câmera externa/MediaStore com arquivos temporários em claro.

Adaptador Android em `:data-documents`, estado/preview em `:feature-import`. Solicitar CAMERA sob demanda e declarar câmera opcional; sem permissões de armazenamento público, localização ou microfone. Escolher resolução limitada, validar bytes/dimensões e fechar `ImageProxy` em todos os caminhos. Normalizar uma única vez a orientação a partir dos metadados da captura, produzir JPEG orientado limitado, sem GPS/EXIF de identidade, e alinhar preview/OCR à imagem efetivamente persistida. Não rotacionar novamente no pipeline.

Um candidato permanece apenas em memória; usar/refazer/cancelar são explícitos. Adicionar entrada validada por bytes ao importador protegido, reutilizando assinatura/MIME/hash, journal, cifragem, commit e limpeza existentes, sem simular uma URI externa ou duplicar transação. IDs de sessão invalidam callbacks tardios. Morte antes de confirmar descarta o candidato; depois de confirmar aplica a recuperação já existente. Alternativa rejeitada: `TakePicture` com FileProvider em cache em claro.

### 5. Áudio local com disponibilidade real e ciclo de vida curto

Adaptador Android junto à feature de resultados, interface testável e estado transitório, sem serviço de reprodução de fundo. O [Android orienta selecionar uma voz embarcada com `isNetworkConnectionRequired()` e `setVoice`](https://developer.android.com/reference/android/speech/tts/TextToSpeech.Engine). Escolher pt-BR instalada e local, verificar retorno/erros, nunca habilitar download ou fallback de rede. Voz e engine selecionados são metadados operacionais, não prova de garantia universal de fornecedor; validar o engine real do S25 em modo avião.

Usar `speak`, não `synthesizeToFile`. Segmentar o snapshot por limites da API, preservando ordem e todos os segmentos, com erro explícito se uma unidade não puder ser apresentada. Botão alterna Ouvir/Parar; nova ação reinicia a leitura, não promete pausa/resume inexistente no contrato. Parar/limpar fila na navegação, ON_STOP, perda de foco, alteração de snapshot ou exclusão; callbacks levam geração da sessão para impedir reativação tardia. Sem texto em SavedStateHandle, DataStore, logs ou arquivo de áudio.

Se não houver voz local, mensagem e texto continuam disponíveis. Contudo, a entrega exige reprodução audível real em ao menos um S25 com voz pt-BR local; teste somente de indisponibilidade não conclui a funcionalidade. TalkBack não depende desse recurso.

### 6. Compartilhar TXT por provider restrito, sem cache clínico em claro

A prévia é explícita; a confirmação prepara um snapshot cifrado de até 64 KiB no diretório do documento com token aleatório. Um índice também cifrado registra versão, posse, expiração e geração de sessão. Reusar `EncryptedArtifactStore` e bloqueios/barreira de exclusão; não é necessária nova estrutura Room para essa abordagem. Se a implementação exigir mudança estrutural, fornecer migração explícita e testes, nunca fallback destrutivo.

Provider Android com `exported=false`, concessões específicas e somente leitura; URI contém token opaco, sem IDs clínicos/nomes. Entregar via ACTION_SEND, EXTRA_STREAM/ClipData e FLAG_GRANT_READ_URI_PERMISSION, sem texto clínico nos extras ou `file://`. O [ContentProvider oferece pipes para transmitir conteúdo gerado](https://developer.android.com/reference/android/content/ContentProvider); será usado stream de descriptografia em memória, não FileProvider sobre arquivo em claro. Validar token, concessão, sessão e documento antes da leitura, negar escrita e limitar metadados ao MIME/nome genérico. Testar com destinatário local sintético que realmente abra o stream.

Validade máxima dez minutos por relógio monotônico da sessão; a nova geração de processo invalida concessões anteriores antes de servir qualquer byte. Reconciliação limpa índice/artefatos órfãos no início; processo morto/reboot exige compartilhar novamente. Isso evita prolongar prazo por ajuste do relógio. Revogação, expiração e tombstone bloqueiam novas aberturas e encerram escritores ainda controlados. Índice/exports pertencem ao documento e entram na exclusão/outbox existente; limpeza pode ser retentada sem republicar conteúdo.

URI temporária não apaga bytes já entregues ao sistema/destinatário. Informar esse limite na prévia; o app externo escolhido pode copiar ou transmitir o resumo por sua conta. Não abrir destino automaticamente, prometer destinatário específico ou esconder permissão ampla atrás do checkbox de importação do HTML. Alternativas rejeitadas: compartilhamento automático, EXTRA_TEXT com conteúdo clínico, exportação em Downloads e PDF em claro.

### 7. Usar o formato do exame pessoal no aceite

A inspeção local de `examples/` encontrou um PDF A4 de seis páginas, dentro do limite de importação, com texto extraível em todas elas. O PDF usa criptografia RC4 e permite leitura e cópia sem informar senha. A leitura no computador não comprova que o adaptador `PrivatePdf` do Android abre esse perfil. O texto contém blocos de material e método, colunas, vírgula decimal e referências com condições. A proximidade entre o nome de uma medida e o de uma estimativa cria um caso concreto para verificar a associação à fonte.

Gerar do zero um PDF de desenvolvimento com esse perfil de paginação, texto e permissões. Usar identificadores fictícios, valores e intervalos inventados para o teste, com gabarito por página e bloco. Incluir controles pareados de glicose medida e glicemia média estimada, referências condicionais e uma página apenas com conteúdo fora do escopo. Assim é possível detectar tanto a perda de um resultado válido quanto a inclusão indevida de uma medida derivada. Manter o caso identificado como regressão de desenvolvimento, fora das métricas do corpus de referência e de HELD_OUT.

O original foi usado como referência de estrutura da fixture. A geração, os testes reproduzíveis, o APK, o CI e o aceite sintético no S25 funcionam sem `/examples/`, pasta ignorada pelo Git. Produzir fotos, capturas de tela, áudio e TXT de teste exclusivamente a partir do sintético. Registrar evidências por identificador fictício, página e estado esperado, sem copiar texto, imagens, metadados, nome de arquivo ou valores do original.

### 8. Ensaio local com exame do titular

A autorização explícita do titular em 21/11/2026 permite um ensaio suplementar com o PDF original no S25 offline. O pacote descartável recebe um stream USB por um FIFO privado de teste e usa a mesma importação cifrada. O relatório retém somente contagens de páginas e observações, estados e códigos operacionais. A fixture sintética continua sendo o gabarito da regressão e dos testes de áudio e exportação.

## Risks / Trade-offs

- [Fidelidade versus governança] → Registrar tabela de adaptação; preservar composição visual, substituindo linguagem clínica por revisão e comparações referenciais.
- [Voz local ausente/engine de terceiro] → Estado indisponível explícito, nenhuma chamada para voz de rede, ensaio audível real offline no S25; sem garantia de voz em todo Android.
- [CameraX, fontes e APK já com 2129770549 bytes] → Medir incremento, verificar dependências/manifest/alinhamento e reinstalar o APK; não remover peso offline ou subir minSdk para fazer o gate passar.
- [Foto de baixa qualidade] → Dicas, prévia/refazer, limites de raster e mesmos fallbacks; não prometer melhor acurácia por mudar UI.
- [Exfiltração após compartilhamento autorizado] → Preview e aviso sobre destinatário; concessão estreita/expiração, sem prometer apagar cópias externas.
- [Telas/ações antigas saírem da navegação] → Matriz de rotas e regressões dos controles `WorkbenchReopenTest`, `CorpusWorkbenchTest` e `FinalWorkbenchAcceptanceTest`; manter tags semânticas estáveis quando possível.
- [Mudança estética ocultar 7/8 fallbacks anteriores] → Chips e resumo ligados às tentativas/configuração reais; novos ensaios não alegam melhoria clínica ou qualidade por estética.

## Migration Plan

1. Implementar em branch `feat/android-prototype-offline-experience`, sem alterar o HTML ou a change concluída. Criar tokens/shell e validar rotas existentes antes de acrescentar adaptadores.
2. Introduzir projeções puras e entradas/adaptadores novos; preservar schema/payloads existentes com defaults compatíveis quando necessário. Snapshots de compartilhamento são derivados, não substituem dados clínicos.
3. Integrar câmera, áudio e exportação com estados de erro/cancelamento e exclusão. Testar atualização sobre base sintética já existente, além de instalação limpa, sem apagar o app atual do usuário.
4. Fazer gates em API 24 e pacote separado no S25: offline desde a abertura, foto real de fixture sintética, reprodução audível, destinatário local, fonte 200%, TalkBack e inspeção de armazenamento. Registrar dados indisponíveis e limitações sem falso sucesso.
5. Rollback por reversão dos commits: manter documentos/revisões anteriores; invalidar tokens de exportação e limpar derivados novos. Não fazer downgrade destrutivo de banco nem remover chaves/arquivos para contornar incompatibilidade.
