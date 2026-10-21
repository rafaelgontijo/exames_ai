# Bloqueio atual de obtenção dos pesos

O candidato principal continua sendo `litert-community/LFM2.5-1.2B-Thinking`, revisão `f8b5b3a3eea2e3859e821ad9d9222c64d649eff7`, arquivo `LFM2.5-1.2B-Thinking_int4.litertlm`. Tamanho e hash publicados estão no desenho OpenSpec; a verificação do binário ainda não ocorreu.

Na tentativa de abrir a URL normal de resolução do Hugging Face em 21/10/2026, houve redirecionamento para `us.aws.cdn.hf.co`. O proxy rejeitou a conexão a esse host com HTTP 403. A configuração inspecionada permite `huggingface.co` e `*.huggingface.co`, mas não inclui o domínio CDN observado. Não foram registrados URLs assinados, parâmetros de autenticação ou tokens.

Ação necessária no ambiente: permitir HTTPS para `us.aws.cdn.hf.co` pelo fluxo de configuração de rede. Depois, repetir a obtenção normal, conferir o SHA-256 do arquivo real e executar o spike. Nenhum peso foi adicionado ao Git; o AAR inspecionado também permanece fora do repositório. Não foi tentado acesso direto fora do proxy nem um caminho alternativo para contornar a política.

Esse bloqueio mantém a tarefa 4.6 pendente. O modelo não foi executado, integrado ao APK ou selecionado definitivamente por ensaio no S25. A existência de manifesto/card e o alinhamento do AAR não substituem esses aceites. Testes da persistência e do corpus são independentes desse download e foram continuados antes da pausa.
