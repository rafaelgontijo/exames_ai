# Bancada Android: importação, histórico e fonte privada

## Fluxo disponível

A tela de importação usa o seletor do sistema para PDF/JPEG/PNG. O callback chama
`ProtectedDocumentImporter`: assinatura, tamanho real e hash são validados antes
de publicar a cópia criptografada. A URI externa não entra no estado da tela,
nas rotas ou no agendamento. O documento salvo continua disponível se a criação
da execução falhar; pode ser processado pelo histórico sem nova importação.

`StartProcessingRun` cria uma execução imutável QUEUED antes de enfileirar o
worker. Falha de agendamento conserva a execução; reconciliação ou o botão de
nova tentativa agenda o mesmo ID. Repetir esse ID não duplica a execução nem
substitui resultado terminal. Fonte ausente, configuração incompatível e modo
assistido ainda indisponível falham antes de agendar. Gabaritos não participam
da execução original.

Nesta etapa, o modo oferecido é **sem IA**. O pipeline por página usa texto de PDF
ou OCR local e o mesmo avaliador determinístico. A tela observa run/checkpoints
via StateFlow e distingue fila, processamento, conclusão, parcial, falha e
cancelamento, com contagem de páginas. Cancelamento é durável; reprocessar cria
execução filha sem alterar a original. Não se conclui a entrega P0 de IA com
este caminho: protocolo, contexto/segmentação e aceite do modelo real seguem
pendentes.

## Histórico e reabertura (7.4)

O histórico lê documentos do repositório protegido e lista suas execuções.
Apresenta formato/data de importação, modo, estado e vínculo de reprocessamento;
não depende de nome de arquivo externo. Uma nova execução sem IA pode ser
iniciada sobre o original privado, inclusive após interrupção entre importação
e criação do run.

A tela de resultados reabre campos brutos, avaliação automática, motivos,
correções append-only, versões de gabarito e comparações salvas. Correções são
exibidas como antes/depois, separadas da avaliação original. Não são aplicadas
silenciosamente para reclassificar, confirmar ou modificar métricas. Gabaritos
pendentes continuam identificados como fora dos denominadores de acerto.

O revisor pode abrir a página da cópia privada. `PrivatePageLoader` autentica o
original e limita recursos; a UI recebe uma cópia imutável do bitmap limitado,
em memória, e fecha os recursos do decoder. Não há cache de prévia em claro ou
dependência da URI inicial. A revisão visual foi ampliada posteriormente com
destaque por campo/página, versão de gabarito e acesso às fontes das diferenças;
ver `EVIDENCE_REVIEW.md`. Edição de extração, revisão independente de gabarito,
comparação por versão e reavaliação/reextração estão conectadas em ações distintas;
ver `REVIEW_WORKFLOW.md`.

Excluir exige confirmação na interface. O repositório oculta o documento e
registra tombstone/outbox antes de cancelar trabalhos; a limpeza privada é
recuperável. Documento, execuções e conteúdo reaberto desaparecem da UI; uma
prévia tardia não republica uma execução removida. Resumos/diários, correções,
gabaritos e comparações seguem a cascata e a barreira já testadas. Pesos
compartilhados não são derivados do documento. Delete não promete sobrescrita
física de flash nem apagamento instantâneo de RAM.

## Estado e falhas

ViewModels usam o factory Hilt da Activity e StateFlow. Dados clínicos e bitmaps
não são gravados em SavedStateHandle, preferências ou Bundle. O NavHost recebe
somente `results/<runId>`, validado como identidade opaca. Erros do storage
produzem indisponibilidade com mensagem fixa; não viram histórico vazio bem
sucedido, não expõem corpo de exceção e não recriam dados em claro.

## Ensaio reproduzível

Com SDK/JDK configurados e os pesos fixados no cache externo ao Git:

```sh
./gradlew test assembleDebug :app:assembleDebugAndroidTest \
  -PvalidationApplicationId=br.com.exames.offline.validation.workbench \
  --no-daemon --console=plain
python3 tools/workbench/run_reopen.py --adb "$ANDROID_HOME/platform-tools/adb" \
  --serial emulator-5554 --report /tmp/workbench-reopen-novo.json
```

O controlador exige emulador e applicationId separado. Confere identidade/hash
do APK, preserva pacotes pré-existentes e remove somente os criados por sua
invocação. Relatório existente não é sobrescrito. A fixture é um provedor Java
somente no APK de testes, com PDF sintético em pipe; não aceita documentos do
usuário. O callback usado pelo seletor é invocado diretamente: o ensaio não
automatiza o DocumentsUI do Android.

Em 31/10/2026 passaram navegação e as fases prepare/resume, com force-stop entre
PIDs 2795 e 2872. Foram reabertas cinco observações, uma correção, um gabarito e
uma comparação após revogar o acesso externo. A prévia privada foi exibida;
Bundle foi inspecionado durante recriação real da Activity, sem os marcadores
clínicos/URI ou bitmap. Cancelar a confirmação de exclusão conservou o documento;
confirmá-la removeu UI/original e preservou a sentinela de pesos. Relatório:
`validation/results/workbench-reopen-emulator-2026-10-31.json`.

Os pacotes retidos durante diagnóstico foram removidos somente após conferir
que não existiam no início da sessão; evidência em
`validation/results/workbench-session-cleanup-2026-10-31.json`. Os relatórios de
tentativas anteriores falharam por fixture/teste e não são aceite do fluxo.

Build padrão e 213 testes JVM distintos passaram na conferência final, incluindo
184 no domínio. O ensaio é de integração/reabertura/exclusão, não de acurácia:
o histórico de revisão da fixture foi inserido para testar sua preservação.
Nenhum modelo ou S25 executou nesta etapa; não houve benchmark, ensaio completo
de TalkBack/fonte ampliada ou validação clínica.

**Pendências preservadas:** pacotes locais de corpus (7.1),
relatórios e acessibilidade (7.5), estados assistidos/preparação/fallback (7.6) e gates de
inferência real. Os testes finais no S25 continuam reservados para o final.
