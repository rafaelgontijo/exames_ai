# Leitura do resumo no aparelho

Em Resultados, **Ouvir resumo** inicia a fala da execução aberta. **Parar leitura** interrompe a fila. Tocar em Ouvir novamente começa do início. O texto vem de `SummaryText` e é o mesmo da prévia do TXT, inclusive nos campos que precisam de revisão.

O adaptador consulta os serviços de síntese instalados. Para selecionar uma voz, exige idioma português, país Brasil, síntese local, ausência do marcador `KEY_FEATURE_NOT_INSTALLED` e sucesso ao selecionar a voz. Antes de enviar cada segmento, confere novamente a voz. A interface informa indisponibilidade quando a configuração falha. O manifesto acrescenta apenas a consulta ao serviço TTS. O app não solicita instalação de vozes.

A segmentação verifica o texto inteiro antes da reprodução, preserva espaços e ordem e respeita o limite informado pelo Android. Cada segmento recebe um identificador opaco da sessão. A perda do foco de áudio, a saída da tela, o evento de segundo plano e a troca ou exclusão da execução invalidam a fila. A reprodução usa `speak`. O controlador mantém a fila em memória durante a sessão.

## Ensaio físico

O aceite no S25 exige voz pt-BR local instalada, modo avião e Wi-Fi desligado. Com a fixture sintética, selecionar Ouvir, conferir auditivamente o início e um campo com revisão, acionar Parar e conferir o silêncio. Repetir com troca de tela, ida ao segundo plano e exclusão. Registrar o motor, a voz, a versão do Android e a observação audível. Uma indicação de síntese concluída pelo motor, sozinha, é insuficiente para esse aceite.

O Android 7 foi usado para verificar a interrupção do controlador por troca de execução, exclusão, saída da tela e evento de segundo plano com uma saída de voz simulada. Os testes JVM também cobrem o descarte da fila após perda de foco e a ausência de síntese quando a inicialização falha. A confirmação audível no aparelho de referência está pendente.

Referência para seleção de voz e detecção de dados ausentes. [Android TextToSpeech.Engine](https://developer.android.com/reference/android/speech/tts/TextToSpeech.Engine)

Em 21/11/2026, no Samsung SM-S931B com Android 17, modo avião ativo e Wi-Fi desligado, a leitura do laudo sintético iniciou com uma voz pt-BR instalada e local. O Android vinculou o pacote de teste ao serviço `com.google.android.tts`. O serviço padrão da Samsung nessa configuração não aceitou o vínculo do pacote. O usuário confirmou que ouviu a fala e que ela parou ao tocar em **Parar leitura**. O teste de interface registrou início e retorno ao estado de repouso. [Registro do ensaio](../validation/results/prototype-offline-audio-s25-2026-11-21.json)
