# Tasks

## 1. Identidade visual e componentes compartilhados

- [x] 1.1 Criar `:core-ui` e conectar app/features sem dependências circulares; verificar compilação e independência Android de `:domain`.
- [ ] 1.2 Implementar tokens claros/escuros, tipografia e componentes do HTML descritos em design.md; adaptar ThemeContrastTest para medir os pares reais, texto 4,5:1 e controles 3:1.
- [ ] 1.3 Empacotar Figtree Android local com pesos necessários, origem/licença e hashes fixados; verificar carregamento API 24 e ausência de fonte/download em rede.
- [ ] 1.4 Documentar correspondência das quatro telas e substituições de linguagem em docs/PROTOTYPE_EXPERIENCE.md; conferir dimensões/tokens com o HTML de hash registrado, sem alterá-lo ou versioná-lo.

## 2. Resumo referencial e dados de apresentação

- [x] 2.1 Adicionar projeção pura de resumo por execução/base/modo/versão, sem nova regra clínica; testar limites, censura, incompatibilidade de unidades, referências ausentes, ambiguidades, parcial e dados não suportados em `:domain:test`.
- [x] 2.2 Definir texto compartilhado por prévia/áudio/exportação, com limite 64 KiB UTF-8, marcadores para texto livre ambíguo e identidade da saída; testar fronteira exata, Unicode, ausência de PII/cabeçalhos/URIs/pensamento e proibição de truncamento silencioso.
- [x] 2.3 Criar projeção do Início sobre documentos e última extração automática identificada por modo; testar vazio versus indisponível, fila/falha/parcial, datas de importação e preservação de execuções corrigidas separadas.
- [x] 2.4 Documentar contrato do resumo, contagens e limites em docs/REFERENCE_SUMMARY.md; verificar exemplos sintéticos sem diagnósticos ou referências externas.

## 3. Navegação e quatro telas Compose

- [ ] 3.1 Implementar shell Início/Exames/Ajuda e rotas de Enviar/Resultado com IDs opacos; testar Voltar, troca de abas, recriação e ausência de conteúdo clínico em Bundle/SavedStateHandle/rotas.
- [ ] 3.2 Construir Início e Enviar com componentes do protótipo, documentos reais, acesso ao SAF/corpus e modos/preparação/fallback existentes; testar estados vazios/indisponíveis e ausência dos exemplos clínicos fixos do HTML.
- [ ] 3.3 Adaptar cartões e resumo de Resultados, exibindo estados referenciais, motivos, completude, base da saída e participação real da IA; testar NEEDS_REVIEW/NOT_CLASSIFIABLE, valores extensos e proibição de “Tudo normal”/garantia clínica.
- [ ] 3.4 Integrar Ajuda de revisão e acesso aos detalhes da bancada, histórico e exclusão; verificar por testes Compose o acesso a fonte, correções, gabaritos, versões, relatórios, cancelamento e reexecução sem apagar originais.
- [ ] 3.5 Verificar as quatro telas em temas claro/escuro, fonte 200% e TalkBack, com alvos 48 dp e rolagem; registrar capturas sintéticas e matriz de rotas em docs/PROTOTYPE_EXPERIENCE.md.

## 4. Captura privada pela câmera

- [ ] 4.1 Fixar/auditar CameraX e acrescentar CAMERA sob demanda e câmera opcional; verificar minSdk 24, árvore efetiva sem clientes de rede/telemetria e ausência de permissões de localização, microfone ou armazenamento público.
- [ ] 4.2 Implementar adaptador de captura traseira em memória, limites de resolução/bytes e normalização única de orientação sem GPS; testar orientações, captura inválida, fechamento de ImageProxy e ausência de arquivo em claro.
- [ ] 4.3 Adicionar entrada validada de bytes ao ProtectedDocumentImporter reutilizando journal/cifragem/commit; testar hash, falha de persistência, cancelamento, reabertura e recuperação sem duplicação ou fallback em claro.
- [ ] 4.4 Conectar prévia/usar/refazer/cancelar ao pipeline no modo escolhido; testar permissão negada/revogada, morte antes/depois de confirmar, saída da tela e callbacks tardios sem publicação.
- [ ] 4.5 Executar captura de fixture sintética e conferência de fonte/OCR no S25, com testes de API 24 onde há câmera disponível; documentar resultados e limites em docs/PRIVATE_CAMERA.md, sem declarar ganho de acurácia ou montagem multipágina.

## 5. Leitura em voz alta offline

- [ ] 5.1 Implementar adaptador TTS com seleção explícita de voz pt-BR instalada sem rede e visibilidade de serviço mínima; testar voz ausente, somente voz de rede, falha de inicialização e recusa de qualquer download/fallback em rede.
- [ ] 5.2 Conectar Ouvir/Parar ao snapshot, com segmentação pelos limites da API e estado acessível; testar ordem/conteúdo completos, falha antes de reprodução parcial indevida e identificação de ambiguidades, base e modo sem narrativas clínicas.
- [ ] 5.3 Implementar interrupção por navegação, ON_STOP, foco de áudio, mudança/exclusão da execução e cancelamento; testar callbacks atrasados e ausência de fila/texto em estado restaurável, logs ou arquivos de áudio.
- [ ] 5.4 Executar reprodução audível real pt-BR e ação Parar no S25 em modo avião/Wi-Fi desligado; documentar engine/voz, indisponibilidade e limites em docs/OFFLINE_AUDIO.md, sem concluir com stub ou indisponibilidade exclusiva.

## 6. Compartilhamento controlado

- [ ] 6.1 Implementar prévia versionada e confirmação específica de compartilhar TXT; testar seleção automática/corrigida/reavaliada, casos parciais, cancelamento e aviso de controle externo sem conceder acesso antes da confirmação.
- [ ] 6.2 Implementar snapshots e índice cifrados por documento, tokens aleatórios e validade monotônica máxima de dez minutos; testar posse, tamanho UTF-8, erro de chave/espaço e invalidação por expiração ou nova sessão de processo.
- [ ] 6.3 Implementar provider privado com concessão por URI somente leitura e entrega por pipe/ACTION_SEND; testar com destinatário sintético leitura real, URI sem permissão, tentativas de escrita/path traversal e ausência de conteúdo clínico nos extras/caches em claro.
- [ ] 6.4 Integrar revogação, fechamento de escritores e limpeza à exclusão/tombstone/outbox; testar corrida de leitura, callback tardio, falha de limpeza, reinício e preservação dos pesos estáticos, sem prometer apagar bytes já entregues externamente.
- [ ] 6.5 Validar compartilhamento com destinatário local no S25 sem rede e documentar limites/expiração em docs/CONTROLLED_SHARING.md; verificar que nenhum envio ou app destinatário é escolhido automaticamente.

## 7. Aceite integrado e atualização preservada

- [ ] 7.1 Executar `./gradlew assembleDebug test :app:assembleDebugAndroidTest`, instrumentados relevantes API 24 e auditoria do APK/dependências/fontes/manifest/backup/alinhamento; registrar tamanho e não elevar minSdk, remover modelo offline ou adicionar Internet para fazer o build passar.
- [ ] 7.2 Executar pacote separado no S25 em instalação limpa offline e atualização sobre base sintética: câmera real, SAF/PDFs/imagens, ambos os modos, voz audível, destinatário local, revisão, force-stop/reabertura, exclusão durante fala/compartilhamento e pesos íntegros; adaptar controles existentes sem afrouxar critérios e registrar evidências reproduzíveis.
- [ ] 7.3 Consolidar README/docs/VALIDACAO e fidelidade visual com capturas sintéticas, comparar saídas/contagens/abstenções antes/depois e preservar famílias/partições; verificar HTML intacto/não rastreado, ausência de perda de dados e distinção entre aceite técnico, indisponibilidades e validação clínica.
