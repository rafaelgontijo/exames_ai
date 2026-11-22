# Obtenção e verificação dos pesos — 21/10/2026

## Revalidação em macOS — 26/10/2026

Retomada em `feat/android-biochemistry-validation-proposal`, no commit remoto
`1d4f301ae704de2ed4695ce230fe5c124e43ed6b`, posterior ao `6a8f052` informado.
A pasta não rastreada `output/` foi preservada. O OpenSpec já registra 15/51
tarefas completas, incluindo 4.6.

Este host é macOS arm64, sem `/workspace` ou o cache cloud anterior. O cache
equivalente está em
`/Users/rafaelgontijo/Workspace/.model-cache/lfm2.5-1.2b-thinking/`, fora do
repositório. O download normal pela URL fixada abaixo retornou HTTP 200 e
736015744 bytes. A leitura integral do novo arquivo confirmou SHA-256
`da3c0ad154b78f33537b117fbe747cc1d4fd3507479002b159998f4023f32a84`.
O parcial só foi promovido depois da conferência; licença e manifesto locais
também coincidem com os hashes do lock.

No sandbox padrão, a tentativa falhou com `Could not resolve host:
huggingface.co`. Com autorização do executor (`require_escalated`), funcionou.
Não há proxy nas variáveis de ambiente consultadas nem foi disponibilizado um
status da política cloud neste host; não se presume que a antiga allowlist esteja
aplicada aqui. Não foram alterados proxy, TLS, DNS ou destino para obter o peso,
nem registrados URLs assinados. A evidência efetiva é o download completo com
autorização, não uma afirmação sobre a configuração do ambiente anterior.

O S25 foi conectado durante a retomada e reconhecido por ADB como `SM-S931B`.
O spike e seus resultados são documentados separadamente em
`LITERT_COMPATIBILITY.md`; download e integridade não comprovam inferência.

A variante GPU foi obtida posteriormente pela mesma rota oficial e revisão,
com HTTP 200. A leitura integral confirmou 736220768 bytes e SHA-256
`51130e6e57a940bbfbb66f67698c47fd10c7051a70e7247fc8b084ccce4d54ae`, em
concordância com `litertlm_manifest.json`. O pin experimental está em
`validation/models/lfm2.5-1.2b-thinking/gpu-spike.lock.json`. Ambos os pesos
permanecem no cache externo. A variante GPU não foi selecionada para distribuição;
seu ensaio reprovado e o sucesso limitado da CPU estão em `LFM_S25_SPIKE.md`.

Na comparação posterior foram obtidos também:

| Candidato | Revisão | Tamanho real | SHA-256 real |
| --- | --- | --- | --- |
| Qwen3 0.6B dynamic INT4 | `e6687177e00897731e7716267eebd4eba0dde7c2` | 344671744 | `03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856` |
| Gemma 4 E2B GPU | `1caa527c1e88b55f046fcd8d2b4265606680663d` | 2008432640 | `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5` |

Ambos retornaram HTTP 200 pela rota oficial e foram promovidos de `.part` somente
após leitura integral/hash e confronto com metadados LFS fixados. Caches externos:
`/Users/rafaelgontijo/Workspace/.model-cache/qwen3-0.6b/` e
`/Users/rafaelgontijo/Workspace/.model-cache/gemma4-e2b/`. Pins/proveniência/licenças
estão em `validation/models/qwen3-0.6b/` e `validation/models/gemma4-e2b/`.
Os cards declaram Apache-2.0; as cópias da licença identificam suas origens.
Não foram usados mirrors, credenciais ou contorno de gate/política.

A escolha técnica resultante é Gemma GPU para integração, sem empacotamento ainda,
registrada em `validation/models/selection.json`. Ver comparação real, limitações
e reprodução em `MODEL_COMPARISON_S25.md`; integridade do arquivo não substitui
os testes de inferência nem a distribuição autossuficiente futura.

## Evidência anterior no ambiente cloud

O download normal do candidato principal foi concluído com HTTP 200. O arquivo
real foi lido integralmente para calcular SHA-256 e confrontado com o pin do
projeto, os metadados LFS e o manifesto do fornecedor na mesma revisão:

| Campo | Valor verificado |
| --- | --- |
| Repositório | `litert-community/LFM2.5-1.2B-Thinking` |
| Revisão | `f8b5b3a3eea2e3859e821ad9d9222c64d649eff7` |
| Arquivo | `LFM2.5-1.2B-Thinking_int4.litertlm` |
| Tamanho real | 736015744 bytes |
| SHA-256 real | `da3c0ad154b78f33537b117fbe747cc1d4fd3507479002b159998f4023f32a84` |
| Backend inicial | CPU |
| Cache local | `/workspace/.model-cache/lfm2.5-1.2b-thinking/` |

O manifesto fixado está em
`validation/models/lfm2.5-1.2b-thinking/model.lock.json`, junto de cópias sem
modificação de `LICENSE` e `litertlm_manifest.json` da revisão. O manifesto local
registra hashes desses dois documentos. Pesos e arquivo parcial ficam fora do Git;
`.gitignore` também exclui `*.litertlm` e `*.litertlm.part`. O cache pode não
sobreviver à substituição do ambiente e precisa ser verificado novamente.

## Rede observada

A política de inicialização atual inclui `*.hf.co`, `*.huggingface.co`, `hf.co`
e `huggingface.co`. O serviço de status informa observações atuais, mas estado de
aplicação da política `unknown`; esse status sozinho não prova conectividade.
No sandbox padrão, Git e Hugging Face falharam ao conectar a `proxy:8080`.
Repetindo os comandos com acesso à rede autorizado
(`with_additional_permissions`, `network.enabled=true`), o fetch recuperou o
commit `6a8f0528a8c6da2f77bec716a4cc8959c1794164` e o download completo funcionou.
Foram preservados proxy herdado e verificação TLS, sem acesso direto ou alteração
da lista de domínios. Não foram registrados tokens ou URLs assinados.

Isso supera o bloqueio anterior de HTTP 403 no redirecionamento para
`us.aws.cdn.hf.co`; a falha desta retomada, antes da autorização de rede, ocorria
antes de alcançar o destino.

## Repetir a obtenção

Executar na raiz do projeto, com rede autorizada no executor e proxy/TLS herdados:

```bash
mkdir -p /workspace/.model-cache/lfm2.5-1.2b-thinking
curl --fail --location --silent --show-error --connect-timeout 30 --max-time 1800 \
  --output /workspace/.model-cache/lfm2.5-1.2b-thinking/LFM2.5-1.2B-Thinking_int4.litertlm.part \
  'https://huggingface.co/litert-community/LFM2.5-1.2B-Thinking/resolve/f8b5b3a3eea2e3859e821ad9d9222c64d649eff7/LFM2.5-1.2B-Thinking_int4.litertlm'
```

Somente após sucesso do curl, verificar e promover o parcial:

```bash
python3 - <<'PY'
import hashlib
import json
from pathlib import Path

lock = json.loads(Path('validation/models/lfm2.5-1.2b-thinking/model.lock.json').read_text())
partial = Path('/workspace/.model-cache/lfm2.5-1.2b-thinking') / (lock['file'] + '.part')
if partial.stat().st_size != lock['sizeBytes']:
    raise SystemExit('Tamanho divergente; arquivo não promovido')
digest = hashlib.sha256()
with partial.open('rb') as stream:
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(block)
if digest.hexdigest() != lock['sha256']:
    raise SystemExit('SHA-256 divergente; arquivo não promovido')
partial.replace(partial.with_suffix(''))
print('Tamanho e SHA-256 verificados')
PY
```

## Licença, redistribuição e alternativa

LFM usa **LFM Open License v1.0**, não Apache-2.0. A seção 4 exige entregar cópia
da licença, identificar arquivos modificados, preservar os avisos aplicáveis e
incluir os avisos de NOTICE quando houver. A listagem desta revisão contém
`LICENSE`, sem arquivo NOTICE separado. A seção 5 limita a autorização comercial
conforme receita anual e limiar de US$ 10 milhões; a adequação do distribuidor
deve ser conferida antes da distribuição. O download técnico não comprova essa
elegibilidade. Ao empacotar, levar a licença e os avisos ao aplicativo, além do
manifesto; apenas guardar a licença no repositório não cumpre essa entrega.

Qwen3 0.6B permanece alternativa técnica na revisão
`e6687177e00897731e7716267eebd4eba0dde7c2`, arquivo
`Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`, licença Apache-2.0 declarada no card.
Os metadados LFS consultados indicam 344671744 bytes e SHA-256
`03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856`.
Na verificação inicial de 21/10, esses valores eram apenas publicados e o binário
Qwen ainda não havia sido baixado. A obtenção e verificação de 26/10 estão acima.
Sua adoção exige verificação própria, licença/avisos e o mesmo ensaio de evidência,
pt-BR, memória, latência e isolamento; não empacotar dois pesos por padrão.

## Alcance da evidência

A tarefa 4.6 foi concluída pela verificação do arquivo e documentação acima.
O modelo não foi executado, integrado ao APK ou selecionado definitivamente para
distribuição. O ADB não lista aparelho conectado nesta retomada. A tarefa 4.7
continua pendente de spike real no S25, incluindo fixação do AAR, JSON final,
memória, latência e compatibilidade. Nova Engine por solicitação e descarte do
canal de pensamento continuam sendo requisitos; não houve benchmark ou teste
de reset seguro. Ver `LITERT_COMPATIBILITY.md` e o desenho OpenSpec.
