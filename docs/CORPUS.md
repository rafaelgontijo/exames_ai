# Corpus técnico de bioquímica básica

Atualização aprovada: `cases-v2.json` / schema 1.1 / gabaritos versão 2 acrescentam
inputs canônicos independentes para medir o avaliador isolado. As fontes/schemas
v1 foram preservadas. Renderer, dependências, fonte, seed, famílias e partições
continuam os mesmos; somente gabaritos/manifestos mudam. Referências não resolvidas
conservam candidatos e ambiguidades, sem selecionar uma faixa. Dois motivos da
v2 passam a AMBIGUOUS_INPUT pela precedência normativa da regra 2.0; estados
NEEDS_REVIEW e motivos v1 permanecem preservados.

```sh
tools/corpus/.venv/bin/python tools/corpus/generate_v2.py --output validation/generated-v2 --font /caminho/DejaVuSans.ttf
tools/corpus/.venv/bin/python tools/corpus/validate.py validation/generated-v2/corpus.zip
EXAMES_CORPUS_FONT=/caminho/DejaVuSans.ttf tools/corpus/.venv/bin/python -m unittest discover -s tools/corpus -p 'test_*.py' -v
```

A fonte ainda exige o SHA-256 original. `generate_v2.py` delega a renderização ao
gerador v1 inalterado e aplica somente metadados declarados no novo gabarito. O
lock v2 confere os mesmos pins tipográficos/de dependências. Diretório de saída
novo/vazio é obrigatório. `validation/generated-v2/` fica fora do Git. O pacote
gerado nesta sessão tem 216 documentos; os 216 hashes/bytes foram idênticos à v1
regenerada no mesmo host. O ZIP cloud histórico não estava disponível; não houve
confronto com seus binários antigos. Relatório:
`validation/results/ground-truth-v2-2026-10-27.json`.

Este corpus é exclusivamente sintético nesta entrega. Não há pacientes, laudos reais adquiridos ou validação clínica. As faixas são arbitrárias; não são referências para usuários do produto. O processamento Android ainda será conectado nas tarefas seguintes.

## Geração

Usar Python 3.11+ e as dependências exatas de `tools/corpus/requirements.txt`. A configuração de fontes/versões fica em `tools/corpus/toolchain.lock.json`; o gerador verifica o SHA-256 da fonte antes de iniciar. A fonte padrão é DejaVu Sans instalada no Debian em `/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf`. Outra localização pode ser fornecida com `--font`, mas o arquivo deve ter o mesmo hash. Não substituir fontes silenciosamente para comparar hashes.

```bash
python -m venv tools/corpus/.venv
tools/corpus/.venv/bin/pip install -r tools/corpus/requirements.txt
tools/corpus/.venv/bin/python tools/corpus/generate.py
tools/corpus/.venv/bin/python tools/corpus/validate.py validation/generated/corpus.zip
tools/corpus/.venv/bin/python -m unittest discover -s tools/corpus -p 'test_*.py' -v
```

O diretório de saída deve ser novo ou vazio. Para nova geração sem apagar uma anterior, usar `--output /tmp/exames-corpus-novo`. O gerador não apaga diretórios existentes. Os binários ficam em `validation/generated/`, ignorados pelo Git; fixtures, schemas, testes e versões ficam versionados. O pacote gerado pode ser transferido por arquivo para o Android quando a importação da bancada estiver implementada.

A matriz versionada em `validation/corpus/MATRIZ.md` descreve 18 cenários, cinco analitos e dois layouts. Cada cenário gera seis variantes por layout: PDF textual, PDF rasterizado, PNG, JPEG, PNG rotacionado e JPEG degradado. Total: 216 documentos, 1080 observações esperadas. Todas as variantes do cenário pertencem à mesma família, inclusive entre layouts, sem cruzar desenvolvimento e avaliação reservada.

Gabaritos são definidos em `cases.json` antes da renderização. Estados/motivos esperados não são calculados pelo parser/avaliador/modelo. O gerador adiciona evidência geométrica, hash e metadados, sem mudar as respostas. Resultado propositalmente ilegível não é incluído na camada de texto do PDF. As regiões de imagens rotacionadas referem-se às dimensões finais; o ângulo é anti-horário.

Para verificar reprodutibilidade, os testes geram o corpus duas vezes em diretórios temporários e comparam manifesto e SHA-256 do ZIP inteiro. Os PDFs usam metadados invariantes e o ZIP usa ordem e timestamps fixos. Fixar também a toolchain é necessário; hashes não são promessa entre versões diferentes de bibliotecas/fontes.

## Formato do pacote

- `manifest.json`: schema/version, corpus/toolchain, seed, fonte e casos.
- `documents/<caseId>.<ext>`: arquivo e hash/tamanho esperados.
- `truth/<caseId>.json`: versão do gabarito, autoria/estado da revisão e observações/campos/evidências.

Schemas estritos estão em `validation/corpus/schema/`. IDs estáveis usam SHA-256 de componentes UTF-8 precedidos por seu comprimento de quatro bytes big-endian, o mesmo contrato de `StableIds` no domínio JVM. Decimais são strings, preservando precisão; campos ausentes são `null`. O manifesto não contém respostas dentro do caminho de processamento automático: o gabarito pertence ao caminho separado de comparação.

O validador de referência é somente leitura, sem extração de paths do ZIP e sem acesso à rede. Limites: 128 MiB de arquivo e de expansão total, 2500 entradas, 20 MiB por membro e 2 MiB por JSON. Rejeita caminhos absolutos, `..`, aliases, barras Windows, symlinks, entradas duplicadas/criptografadas, arquivos não referenciados, compressão suspeita, hashes/tamanhos/assinaturas inválidos e divergências de schema/identidade/partição. A validação de assinatura não substitui o decoder de PDF/imagem do futuro pipeline Android.

O validador também rejeita chaves JSON duplicadas, números não finitos e evidência fora da página. Mensagens de erro não imprimem conteúdo de exames. O pacote não é assinado; hashes verificam integridade interna, não autenticam autor externo.

## Exemplos públicos e revisão pelo usuário

Não há crawler nem aquisição de laudos dentro do APK. Para incorporar um demonstrativo público:

1. Registrar URL HTTPS, data, hash e condições verificáveis de reutilização. Conferir que o documento é demonstrativo e não contém pessoas identificáveis.
2. No registro de origem `PUBLIC_DEMONSTRATION`, preencher `provenance`: `url`, `obtainedOn`, `reuseTerms`, `eligibilityReviewed` e `containsIdentifiableData`. Não tornar elegível apenas porque o arquivo está público.
3. Anotar campos e evidência contra a fonte. O usuário é o revisor documental inicial. Usar `reviewState=PENDING` até a conferência; esses casos são exploratórios e ficam fora dos denominadores de acerto.
4. Após conferir, registrar `REVIEWED`, `reviewerId` local e `reviewedAt` ISO 8601; qualquer alteração posterior cria outra versão, preservando comparações antigas.
5. Contexto clínico desconhecido continua pendente mesmo após conferir a transcrição. Não completar faixas por conhecimento do revisor ou modelo.
6. Validar o pacote local antes de compartilhá-lo. Não versionar documentos não elegíveis ou laudos pessoais neste repositório.

Os testes usam um registro público fictício em `example.org` apenas para verificar esse protocolo: a revisão pendente não vira acerto, e elegibilidade ausente impede inclusão. Isso não representa aquisição real. O corpus sintético funciona sem qualquer exemplo público.

## Limites de evidência

Os testes verificam geração, contrato e segurança estrutural do pacote. Não medem OCR no Android, precisão em laboratórios reais, latência do LFM, comportamento do Samsung S25 ou validação clínica. Esses itens continuam nas tarefas OpenSpec correspondentes.

## Exposição das primeiras fixtures técnicas — 03/11/2026

O seletor Android inicial tomava o primeiro caso por formato/layout e não filtrava
partição. Seus oito casos eram HELD_OUT e foram usados no diagnóstico de prompts.
Isso expôs as famílias listadas em
`validation/corpus/exposed-held-out-families-2026-11-03.json`. Não usar essas
famílias para alegar generalização em conjunto intocado. Os casos, hashes,
gabaritos, rótulos e relatórios originais não foram reatribuídos ou sobrescritos.
A integridade do pacote e do fluxo técnico não demonstra independência estatística.

`stage_android_fixture.py` agora seleciona somente DEVELOPMENT e exige os oito
estratos formato/layout; ausência de um estrato falha em vez de emprestar casos
HELD_OUT. Testes regressivos verificam ordem adversa (HELD_OUT primeiro), bytes
de documentos/gabaritos preservados e estrato ausente. Novos ensaios têm relatórios
separados e não são comparações diretas de qualidade com as oito fontes anteriores.
A bancada continua aceitando os contratos e partições originais; essa restrição
pertence ao controlador de ajuste de prompt, não altera silenciosamente o corpus.
