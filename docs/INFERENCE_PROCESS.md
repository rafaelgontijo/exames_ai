# Processo dedicado de inferência — implementação parcial de 4.12

`LocalTextInference` delega preparação/cópia/hash, criação de Engine e geração
para `InferenceProcessService`, serviço bound privado em `:inference`. É outro
processo do mesmo UID, com acesso aos assets e pesos privados; não é um sandbox
com permissões diferentes. O processo principal não executa JNI do modelo.
BASELINE retorna antes de preparar pesos ou criar processo.

## Política implementada

| Limite | Comportamento |
| --- | --- |
| Uma solicitação global | Segunda chamada concorrente retorna BUSY; não mantém fila de textos clínicos. |
| Carga: 120 s | Inclui binding, preparação/verificação/cópia e inicialização da Engine. |
| Geração: 120 s | Inicia após initialize, incluindo criação de conversa, send síncrono e fechamento. |
| Encerramento: até 5 s | Mata o PID filho confirmado por handshake e espera morte do Binder; também desfaz binding. Falha em confirmar encerramento bloqueia novas chamadas até reiniciar o processo principal. |
| Entrada IPC: 16384 bytes UTF-8 combinados | Instrução/documento devem ser não vazios; versões limitadas. Excesso é rejeitado, sem truncar. Não equivale a quantidade de tokens. |
| Canal final IPC: 16384 caracteres | Saída excessiva, vazia ou métricas inválidas retornam falha; nunca reparar JSON. |
| Contexto/saída nativos: 2048/1024 tokens | Mesmos parâmetros fixados anteriormente; nenhuma estimativa de tokens é obtida a partir de caracteres/bytes. |

`ProposalProtocol.compile` aplica a quota combinada também durante a montagem:
limita o texto bruto antes de escapá-lo, conta os bytes UTF-8 do JSON escapado e
dos delimitadores e só conserva um prompt completo se couber. Não monta uma
árvore JSON de tamanho arbitrário para depois rejeitá-la no IPC. A validação da
lista de fontes aceita no máximo 16384 fragmentos, incluindo os vazios; essa é
uma barreira de trabalho/alocação, separada do orçamento de tokens. Aliases e
textos dos fragmentos aceitos permanecem integrais, na ordem original.

Excesso na compilação produz somente o código fixo `INPUT_LIMIT`: não chama o
backend, não registra texto na exceção e mantém exatamente a saída do parser,
inclusive suas ambiguidades, no fallback. Não conta como sucesso de IA.
Essa proteção não implementa segmentação nem comprova que o prompt cabe nos
2048 tokens nativos; esses critérios continuam pendentes, sem truncar o laudo.

Cada solicitação tem nonce novo. Somente respostas do mesmo UID e nonce são
aceitas. A mensagem de readiness informa PID diferente do principal; serviços
sem processo separado não executam a Engine. O serviço verifica a mesma quota
de entrada e contém watchdog próprio. Morte do cliente também encerra o filho.
Após sucesso, falha, timeout ou cancelamento, o cliente encerra o processo;
nenhuma Engine/conversa é reutilizada. Respostas tardias são descartadas.

O trabalho nativo fica em thread do filho; o handler de controle permanece livre.
Não depende de `Conversation.cancelProcess` ou de interrupção cooperativa do JNI.
O encerramento é confirmado antes de liberar a concorrência. O serviço não grava
prompt/final/pensamento em disco e não propaga mensagem/causa de exceção do SDK.
Somente `Contents` do canal final sai como `UnvalidatedFinal`, ainda sujeito ao
schema/evidência antes da avaliação. Manifesto não acrescenta rede/permissões.

`ExamesApplication` agenda reconciliação/limpeza somente no processo principal,
evitando disparar manutenção a cada processo de modelo. Serviço/fake de teste
não são exportados; o fake existe somente no APK instrumentado.

## Métricas e configuração

Durações retornadas continuam as do adaptador Java: load mede initialize e
generation mede send síncrono. Não incluem toda a preparação, IPC, conversa e
encerramento; não devem ser apresentadas como duração total da solicitação.
Os prazos do cliente abrangem essas etapas como descrito acima. Os parâmetros
do processo estão em `validation/models/selection.json`, ainda com aceite real
pendente. Não houve troca de modelo/hash/backend ou ativação da bancada.

## Verificado e ainda pendente

Em 30/10/2026, cinco novos testes JVM verificaram a fronteira exata de bytes
combinados, UTF-8/emoji, expansão de caracteres escapados, muitos fragmentos
pequenos/vazios, preservação dos aliases/textos e fallback antes de chamar o
backend. O teste do processamento confronta a saída completa com BASELINE em
um caso inequívoco e em um número ambíguo. Essa evidência é de limites de memória
e contrato, sem executar modelo ou aparelho.

Sete controles instrumentados no emulador API 36 verificaram serviços privados,
sucesso com prefixos iguais em PIDs distintos, carga/geração bloqueadas,
cancelamento, concorrência entre instâncias, crash, erro sanitizado, quotas,
BASELINE sem binding e preparação real sem asset retornando MODEL_UNAVAILABLE
antes de inicializar o motor. Seis desses controles usam serviço simulado;
nenhum carrega Gemma. Preparação de arquivos ausentes/corrompidos continua
coberta separadamente por `ModelPreparationTest`; os códigos atravessam o IPC.

**4.12 permanece aberta.** Falta ensaio com o Gemma real neste novo processo,
incluindo contexto excedido, truncamento/token budget e isolamento sob prefixos
iguais. A API pública inspecionada não fornece tokenizer de entrada anterior ao
envio; `getTokenCount` informa tokens já no KV. Segmentação/orçamento efetivo de
documentos e geração precisam ser aceitos junto ao protocolo 4.10. Byte quota,
sucesso do fake ou JSON sintático não concluem esses critérios. O caminho de
agendamento assistido permanece bloqueado até esses gates. A identidade e o
diário protegido de 4.13 foram implementados posteriormente; ver
[INFERENCE_TRACE.md](INFERENCE_TRACE.md).

Os relatórios antigos do adaptador e instalação limpa no S25 demonstram o caminho
anterior. Não são aceitação automática do novo processo. A repetição real e os
benchmarks ficarão na etapa final do S25 conforme orientação do usuário. Os
testes não prometem comportamento de drivers GPU, pico de RAM em aparelhos de
4 GB ou execução durante force-stop.

Fontes primárias: [serviços bound/Messenger do Android](https://developer.android.com/develop/background-work/services/bound-services)
e [Conversation do LiteRT-LM 0.18.1](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt).
Essa versão documenta que o limite de saída inclui tokens de pensamento e final;
não se presume que ocultar pensamento desative seu cálculo. A implementação
selecionada continua respeitando canais do bundle, sem persistir pensamento.
