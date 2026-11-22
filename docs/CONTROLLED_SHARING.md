# Compartilhamento do resumo TXT

Em Resultados, **Compartilhar resumo** abre a prévia da execução selecionada. **Cancelar** fecha a prévia. **Escolher aplicativo** confirma o texto exibido e abre o seletor Android. O aplicativo escolhido pode guardar ou transmitir os bytes recebidos.

O provedor privado usa a autoridade específica do pacote e aceita uma URI com um único token aleatório. O seletor recebe `ACTION_SEND`, MIME `text/plain`, `EXTRA_STREAM`, `ClipData` e concessão de leitura. Nome público do arquivo: `resumo.txt`. O limite é 65.536 bytes UTF-8. O documento de origem, caminhos privados e texto do resumo ficam fora dos extras.

O armazenamento grava dois artefatos AES-GCM por exportação, no diretório protegido do documento. O índice vincula sessão, execução, documento, token, expiração monotônica, tamanho e SHA-256 do texto. A leitura verifica a posse, o índice e o conteúdo antes de enviar bytes pelo pipe. A expiração é de dez minutos. A nova sessão de processo inicia sem tokens ativos e a reconciliação remove os artefatos órfãos. O trabalho periódico repete a limpeza após falha.

Preparação, abertura, escrita não bloqueante e exclusão usam a mesma barreira. Após o tombstone, a exclusão bloqueia novas leituras e fecha escritores controlados pelo app. A outbox cobre o diretório do documento e seus derivados. O Android ou o destinatário podem já ter recebido parte ou todo o TXT.

## Verificação

`ResultShareStoreTest` passou no emulador API 24 com quatro casos. A suíte confere o limite exato em UTF-8, a falta de acesso por outro documento, o prazo monotônico, a troca de sessão, o fechamento do pipe durante exclusão, falha de escrita e perda da chave. O ensaio entre aplicativos usa um destinatário exclusivo do APK de testes que abre o TXT e calcula seu hash em memória. No S25, o aceite físico exige seleção desse destinatário com a rede desligada e comparação com o hash da prévia.

No S25 com Android 17, modo avião ativo e Wi-Fi desligado, o seletor Android abriu após confirmação da prévia. O leitor local abriu o stream em outro UID e calculou o SHA-256 em memória. O hash coincidiu com o texto da prévia nos dois modos. Antes da concessão e após revogação, a leitura externa falhou. A concessão temporária permitiu leitura e rejeitou escrita. [Registro multipágina](../validation/results/prototype-multipage-s25-2026-11-21-verified.json)
