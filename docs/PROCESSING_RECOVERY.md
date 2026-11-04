# Estados e recuperação do processamento

## Contrato observado

| Condição | Ação/recuperação |
| --- | --- |
| Importação interrompida antes da publicação | Journal com ID opaco permite remover fonte órfã; se o documento foi publicado, preserva a cópia. Falha de chave/leitura não é tratada como ausência. |
| QUEUED sem trabalho agendado | Reconciliação na abertura/periódica agenda trabalho único KEEP; não muda configuração. |
| RUNNING após morte/parada do processo | Retoma a mesma execução e pula checkpoints COMPLETE, inclusive após confirmação de commit perdida. Páginas ainda não confirmadas podem ser repetidas. |
| COMPLETE | Imutável; novo despacho não reprocessa páginas. |
| PARTIAL/FAILED | Páginas válidas preservadas. Retentativa deliberada cria filha com novo ID e reprocessa a mesma fonte inteira, sem substituir a original. |
| CANCELLED por comando explícito | Estado durável antecede cancelamento do scheduler. Não volta a RUNNING; retentativa cria filha. |
| Parada do scheduler sem comando explícito | Propaga cancelamento da coroutine e mantém execução retomável; não fabrica CANCELLED nem publica retorno tardio. |
| Documento oculto com outbox pendente | SQL já bloqueia gravações. Cancelamento/limpeza podem ser repetidos após interrupção; ainda não significa arquivos apagados. |
| Outbox confirmada | Trabalhos cancelados, diretório removido e barreiras permanentes mantidas. Outros documentos/modelos permanecem. |
| Chave indisponível | Condição de indisponibilidade, sem banco vazio, recriação de chave em leitura ou saída em claro. Preserva dados/intenção; não promete recuperar ciphertext sem a chave. |

Não há transação única entre Room, WorkManager e filesystem. A ordem durável,
outboxes, trabalhos únicos e barreiras reparáveis tratam as interrupções entre
esses sistemas. Uma transação de página mantém observações/avaliações/checkpoint
juntos; um checkpoint nunca é reaproveitado para outra configuração/modo.

## Procedimentos verificados com fixtures

1. **Retomada entre processos:** preparar PDF privado com duas páginas, confirmar
   a primeira e injetar interrupção após commit. Encerrar o pacote de testes e
   iniciar outra instrumentação sem reinstalar. Conferir PID diferente, mesmas
   cinco observações da página 1 e dez ao concluir, sem duplicação.
2. **Falha parcial:** PDF com stream intermediário inválido mantém páginas 1/3,
   checkpoint 2 FAILED e execução PARTIAL. Retentativa usa filha; se a mesma
   fonte permanecer inválida, continua parcial, sem reparo inventado.
3. **Cancelamento/exclusão:** pausar processamento/resposta simulada, confirmar
   estado/tombstone e depois liberar callback. Não deve publicar novos registros.
   Escritor de arquivo ativo é serializado com a limpeza; novo escritor falha.
4. **Limpeza interrompida:** injetar falha após apagar arquivos e antes do ack,
   reabrir e repetir limpeza. A outbox permanece até sucesso e o tombstone
   continua após o ack; cancelamento indisponível também mantém retentativa.
5. **Chave perdida:** usar alias de teste isolado, encerrar a base e remover
   somente esse alias. Reabertura/limpeza falham sem alterar banco/fonte/outbox.
   Para artefatos, leitura e nova escrita com ciphertext existente não recriam
   chave. Duas primeiras escritas concorrentes em store novo usam a mesma chave.

Comandos de teste (JDK/SDK configurados, emulador disponível):

```sh
./gradlew test assembleDebug :app:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew :data-local:connectedDebugAndroidTest
./gradlew :data-local:assembleDebugAndroidTest
python3 tools/processing/run_recovery.py --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5554 --report /caminho/novo-relatorio.json
```

O ensaio entre processos é opt-in e fica ignorado no comando instrumentado
comum; o controlador o executa em duas invocações e preserva relatórios existentes.
Somente o pacote sintético de testes criado por ele é removido ao concluir.
Os testes de perda de chave usam aliases/raízes próprios, nunca os dados do app
do usuário.

## Force-stop, indisponibilidade e pendências

Force-stop não autoriza prometer trabalho em segundo plano enquanto o pacote
está parado. O ensaio faz retomada explícita por nova instrumentação. Reabrir o
app agenda reconciliação; ela depende de o sistema voltar a permitir sua execução.
Cancelamento durável impede publicação, mas não demonstra interrupção imediata
de JNI/OCR; prazo/isolamento continuam em 4.12.

Não há recuperação automática de chave perdida, restauração de bytes legados
apagados ou fallback em claro. A UI de indisponibilidade/descarte explícito ainda
deve ser integrada; nenhum reset silencioso é autorizado por esses procedimentos.
Exclusão lógica não promete sobrescrita física de flash nem remoção imediata de
buffers já entregues ao runtime. A ativação assistida e os ensaios no S25 continuam
pendentes para suas etapas, com o S25 reservado para o final.
