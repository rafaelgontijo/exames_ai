# Obtenção e verificação dos pesos — 21/10/2026

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
Esses valores são publicados: o binário Qwen não foi baixado nem verificado.
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
