# Aceite técnico final no S25 — 03/11/2026

O controlador passou em instalação limpa antes da primeira abertura, modo avião
e Wi-Fi desligado: preparação/seleção pela UI, oito pares automáticos, correção,
reavaliação, force-stop, reabertura privada sem fonte externa e exclusão dos oito
documentos, preservando os pesos estáticos antes da remoção dos pacotes de teste.
O pacote padrão e seus dados não foram instalados, apagados ou substituídos.
Modo avião e Wi-Fi foram restaurados ao estado anterior (0/1) após o ensaio.

Evidência reproduzível: [`final-workbench-s25-2026-11-03-protocol5-development-verified.json`](../validation/results/final-workbench-s25-2026-11-03-protocol5-development-verified.json).
S25 SM-S931B, Android 17/API 37; Gemma 4 E2B GPU/LiteRT-LM 0.18.1, prompt
`biochemistry-evidence/5`, schema `biochemistry-fields/1`; revisão/hash/parâmetros
completos em `validation/models/selection.json` e no relatório. Corpus v2/schema
1.1: oito casos DEVELOPMENT, dois layouts e quatro formatos, cinco observações
por caso. Não é ensaio de todos os 216 documentos nem teste independente de
generalização. Exposição histórica de famílias HELD_OUT: ver `CORPUS.md`.

## Resultado A/B

“Corretos” compara a classificação com o gabarito revisado, incluindo abstenções.
“IA aceita” conta páginas com solicitação/configuração/schema/evidência atestados,
nunca o sucesso exclusivo do parser. A maioria dos casos continua em fallback;
aprovar o fluxo técnico não aprova a qualidade do extrator.

| Formato | Layout | Corretos sem IA | Corretos assistido | IA aceita | Desfecho |
| --- | --- | --- | --- | --- | --- |
| JPEG | footnote-v1 | 2/5 | 2/5 | 0 | ENGINE_FAILURE / RUNTIME_FAILURE |
| PNG | footnote-v1 | 5/5 | 5/5 | 0 | ENGINE_FAILURE / RUNTIME_FAILURE |
| PDF_SCANNED | footnote-v1 | 5/5 | 5/5 | 0 | ENGINE_FAILURE / RUNTIME_FAILURE |
| PDF_TEXT | footnote-v1 | 5/5 | 0/5 | 0 | INVALID_EVIDENCE |
| JPEG | simple-v1 | 5/5 | 0/5 | 0 | INVALID_EVIDENCE |
| PNG | simple-v1 | 5/5 | 0/5 | 0 | INVALID_EVIDENCE |
| PDF_SCANNED | simple-v1 | 5/5 | 0/5 | 0 | INVALID_EVIDENCE |
| PDF_TEXT | simple-v1 | 5/5 | 5/5 | 1 | ACCEPTED |

Total: 1/8 páginas aceitas pelo modelo; sete com fallback.
Novas classificações divergentes do gabarito em relação ao modo sem IA: 20;
novos casos anormais apresentados como dentro da referência: 0. Divergências
sem evidência preservam NEEDS_REVIEW. O modo sem IA continua disponível e padrão.
Não selecionar ou omitir esses erros para alegar melhoria de cobertura.

Carga fria do Engine nas respostas finais disponíveis: 8489–12306 ms;
geração até o canal final: 6143–8025 ms. Máximo de PSS
amostrado disponível: 2997011 KiB. Respostas que falharam no engine não têm
medição nativa fabricada. Os dados brutos operacionais por página, RAM disponível,
RAM total, duração monotônica, falhas, estratos, cobertura e denominadores estão
no JSON. Tokens agregados e thought/final vieram indisponíveis e permanecem nulos.
PSS amostrado não é pico nem contabilização completa de GPU; cache do SO e estado
térmico não foram controlados. Não há reutilização de Engine/processo.

## Revisão, reinício e limpeza

O teste mostrou participação real do modelo na UI e separou a reavaliação sem
nova IA. A correção sintética foi persistida antes de reavaliar. Observações,
avaliações, comparações e métricas automáticas permaneceram idênticas antes/depois
e após force-stop. PIDs principal antes/depois: 7134/8294.
A fonte privada reabriu com a URI externa revogada. Os oito documentos e derivados
foram removidos; pesos estáticos ainda conferiram SHA-256 antes de desinstalar
somente os dois pacotes descartáveis criados pelo controlador.

Uma tentativa anterior mediu os mesmos oito pares e falhou na sincronização do
teste após salvar a correção. A espera passou a exigir a recarga completa e a
correção persistida. Modelo, dados, schema e critérios não mudaram. A nova rodada
limpa acima passou inteira; as tentativas reprovadas foram preservadas.

Este é um aceite técnico de bancada interna. Não há validação clínica, cobertura
de laudos públicos ou recomendação de uso clínico. Hardware de 4 GB e kernel de
16 KiB continuam pendentes; a presença/alinhamento de bibliotecas não comprova
execução nesses aparelhos.
