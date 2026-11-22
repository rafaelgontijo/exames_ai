# Codec de payloads locais

`EncryptedPayloadCodec` fornece envelope AES-256-GCM para BLOBs sensíveis. Ele é usado pelo schema protegido v2, pela migração por cópia/troca e pelos repositórios de validação. A integração da importação/UI e a validação em laudos reais ainda estão pendentes.

Formato binário v1: byte de versão, IV de 12 bytes gerado pelo provedor e ciphertext com tag de 128 bits. AAD inclui versão do envelope e componentes UTF-8 `entityType`, `entityId`, `schemaVersion`, cada um prefixado por comprimento big-endian de quatro bytes. Alterar entidade, ID ou versão de schema invalida autenticação. Não colocar conteúdo de laudos nos identificadores.

Cada cifragem recebe IV novo pelo provedor; não há nonce fixo, contador reutilizável ou criptografia própria. O limite é 4 MiB de plaintext por payload, verificado antes de cifrar. Decifragem verifica tamanho e versão, usa `doFinal` e só retorna plaintext após autenticação completa. Mensagens de erro não incorporam plaintext, ciphertext ou identificadores.

`AndroidPayloadKeys` mantém chave não exportável no Android Keystore. Criação é uma operação explícita para um novo armazenamento. `existing()` nunca recria chave ausente; o futuro inicializador de banco deve distinguir primeira instalação de perda de chave. Invalidação/indisponibilidade deve chegar à UI como armazenamento indisponível, sem base vazia ou fallback em claro.

Os testes JVM verificam round-trip, IVs distintos, AAD, adulteração de cada byte do envelope, truncamento, versão inválida, chave errada/ausente e limites. Os testes instrumentados usam o Keystore real, verificam não exportabilidade, associação entre linhas e ausência de regeneração após apagar a chave de teste. Nenhum teste utiliza dados reais. Falta de aparelho não equivale a execução aprovada.

```bash
source /workspace/.android-dev/env.sh
./gradlew :data-local:testDebugUnitTest :data-local:assembleDebugAndroidTest
./gradlew :data-local:connectedDebugAndroidTest
```

O último comando requer aparelho ou emulador. A tarefa OpenSpec 2.1 foi marcada concluída após execução instrumentada no emulador API 24. O mesmo codec é usado na migração e nos repositórios v2; a integração das telas e do pipeline permanece nas tarefas seguintes.
