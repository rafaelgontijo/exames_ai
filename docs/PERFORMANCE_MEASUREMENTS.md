# Medições locais e comparação de modos

Os registros usam `SystemClock.elapsedRealtime`, relógio monotônico. Cada página
registra duração, PSS do processo principal e memória disponível antes/depois,
além de RAM total. A identidade do aparelho/Android pertence à execução.
Falhas possuem códigos fixos; nenhuma mensagem de exceção clínica é registrada.
Reavaliações não fabricam novas medições de OCR/inferência.

Uma geração bem-sucedida pode registrar carga fria, geração até o JSON final,
duração total no filho e máximo de PSS amostrado a cada 100 ms. O processo é
novo por solicitação e seu PID permite verificar isolamento. Esse máximo
amostrado não é pico real; memória GPU não é contabilizada completamente.
Endpoints de PSS do app não incluem o processo separado de inferência.

LiteRT-LM fornece contagens agregadas de prefill/decode e contexto, quando
 disponíveis. Zero não demonstrado vira nulo. O SDK não fornece contagens
separadas dos canais thought/final: ambos permanecem nulos, com motivo explícito
`SDK_NO_CHANNEL_TOKEN_COUNTS`. Não se inspeciona nem persiste raciocínio para
estimar tokens. Ausência de medição não representa zero trabalho.

A comparação exige duas execuções automáticas terminais, documento/hash/caso,
versões de extração/parser/regra idênticos e gabarito revisado escolhido pelo SHA.
Mostra divergências, novos erros, fora→dentro, cobertura, fallback e métricas dos
dois lados. Uma página só conta como aceita pelo modelo com solicitação enviada,
configuração atestada, disposição ACCEPTED e resultado SUCCEEDED. Saídas
corrigidas e sucesso exclusivo do parser não satisfazem esse critério.

As medições ficam nos payloads protegidos; não há telemetria ou logs de conteúdo.
Os controladores de teste exportam somente fixtures sintéticas e metadados
operacionais. Os ensaios finais usam pacotes separados, primeira abertura sem
rede, pares A/B, correção/reinício/exclusão e preservação dos pesos compartilhados.
Scripts: `tools/workbench/run_native_acceptance.py` e `run_final_acceptance.py`.
Compilar esses scripts/testes não comprova inferência real. Aceite no novo caminho
nativo do S25, hardware de 4 GB e contabilização completa de GPU continuam
pendentes até existir evidência correspondente.
