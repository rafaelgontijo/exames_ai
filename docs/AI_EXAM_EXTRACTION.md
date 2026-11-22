# Extração offline de resultados

O Meu Exame recebe um PDF digital pelo seletor do Android. PDFium abre a fonte em memória e preserva o texto e a posição dos glifos em uma prévia orientada da página. O fluxo reconhece o cabeçalho da tabela e organiza células candidatas de nome, resultado atual e referência dentro da geometria da página. A publicação exige uma proposta da IA com esquema válido, IDs presentes no bloco e vínculo com a mesma linha. O nome é livre e o valor e a unidade precisam reproduzir toda a célula de resultado, inclusive comparadores e separadores. A referência preserva o texto completo vinculado, incluindo condições, sem escolher uma faixa aplicável.

## Modelo e limites

| Parâmetro | Configuração inicial |
|---|---|
| Modelo | Gemma 4 E2B GPU da seleção fixada |
| Revisão | `1caa527c1e88b55f046fcd8d2b4265606680663d` |
| SHA-256 | `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5` |
| Runtime | LiteRT-LM Android 0.18.1 |
| Prompt e schema | `exam-extraction/4` e `exam/2` |
| Geração | GPU, 2.048 tokens de contexto, até 1.024 de saída, seed 0, temperatura 0, top-k 1, top-p 1 |
| Isolamento | Uma engine e um processo privado por requisição. Somente canal final |
| PDF | Até 20 MiB e 20 páginas |
| Segmentação | Até cinco linhas inteiras e 1.800 bytes UTF-8 de evidência por requisição, até 12 blocos por página e 64 requisições por documento |

Os limites do contrato protegem a transmissão e delimitam as tentativas. A capacidade efetiva do contexto e a duração por bloco precisam ser aferidas no S25. A região de cada célula fica no aparelho e acompanha o resultado após a segmentação. Os pesos e a configuração nativa seguem a seleção anterior. [Seleção](../validation/models/selection.json) · [Histórico de compatibilidade](LITERT_COMPATIBILITY.md)

## Estados

- A abertura valida o formato, o tamanho e a legibilidade. A tela de andamento mostra a página e o bloco.
- Uma página pode publicar resultados verificados e sinalizar outros trechos. Falha de leitura, falta de texto posicionado, estouro do orçamento, falha da inferência, esquema inválido e associação incerta têm códigos operacionais distintos.
- A junção usa o ID da célula de origem e mantém ocorrências distintas com texto igual. Uma tentativa tardia, após cancelamento, não publica resultados.
- Se o aplicativo for interrompido antes da persistência do resultado, a próxima abertura sinaliza a interrupção. A extração concluída reabre pelo identificador opaco atual.
- Ao cancelar ou descartar, o app encerra o trabalho e remove a fonte e os derivados da extração atual. A cópia privada e o resultado usam AES-GCM com chave do Android Keystore. O app preserva os arquivos e o banco da bancada anterior.

## Prova privada

O laudo autorizado e a lista de conferência por página ficam em ambiente local privado. Aferição e comparação usam um pacote de teste separado no S25, em modo avião com Wi-Fi desligado, e registram somente contagens, estados e configuração. O PDF e as respostas esperadas ficam fora do Git, dos APKs, da CI e dos prompts.

A lista de referência privada cobre 36 medições em 30 linhas, distribuídas pelas páginas em 21, 3, 2, 6, 2 e 2 medições. Seis linhas contêm medidas percentual e absoluta com unidades impressas no cabeçalho. A lista usa regiões anotadas independentemente do extrator, vinculadas à cópia autorizada. A comparação local de 148 regiões via Poppler e PDFKit encontrou concordância literal, descontadas diferenças de espaço entre glifos. A lista é reconstruída em memória a partir do PDF e da anotação ignorada em `examples/`. O laudo e a lista de referência ficam fora dos builds e dos prompts.

Com duas medidas impressas na mesma linha, o extrator usa os cabeçalhos de unidade para dividir a evidência. Nome, medida, unidade e intervalo conservam seus trechos de origem. Cada requisição apresenta células JSON com IDs curtos locais ao bloco. O modelo propõe o valor literal e cita nome, resultado, unidade e referência. A validação reconstrói a unidade a partir do trecho citado, preservando a grafia impressa. A prova no aparelho compara os campos publicados e suas regiões com a lista privada.

Prepare o pacote isolado para a prova no S25 com os dois APKs gerados em `app/build/validation/outputs/apk/`.

```sh
./gradlew -PvalidationApplicationId=br.com.exames.offline.validation.extraction \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Instale o pacote isolado e seu APK de instrumentação no S25 offline. Use o endereço local do ADB e o serial do aparelho para executar a prova inicial na página 1.

```sh
python3 tools/workbench/run_authorized_example.py \
  --adb /caminho/para/adb --serial SERIAL --protocol exam --page 1 \
  --compare-private-checklist --review-on-device --owner-approved \
  --report /tmp/exames-pagina-inicial.json
```

Com `--review-on-device`, a extração fica cifrada no pacote separado para abertura e conferência visual no próprio S25. Descarte a execução na interface após a conferência. Sem essa opção, a instrumentação descarta a cópia durante o encerramento. Com `--compare-private-checklist`, o controlador envia a lista ao pacote de instrumentação depois que a inferência termina. A comparação usa os textos e as regiões, distingue omissões, extras, diferenças de medida e de referência e publica apenas totais por página. A prova completa do extrator usa o mesmo procedimento sem `--page`.

Para testar o fluxo final após a prova completa, execute em uma instalação limpa do pacote de ensaio no S25 offline.

```sh
python3 tools/workbench/run_authorized_example.py \
  --adb /caminho/para/adb --serial SERIAL --protocol exam \
  --via-saf --compare-private-checklist --owner-approved \
  --report /tmp/exames-fluxo-saf.json
```

O ensaio toca em **Selecionar PDF** no app e abre **Laudo autorizado.pdf** em **Ensaio do laudo**, no seletor do Android. O provedor do APK de instrumentação entrega os bytes diretamente do pipe USB. A lista privada entra por um segundo pipe depois da inferência. A automação abre a prévia de uma evidência publicada, testa a volta do seletor ao escolher outro PDF e verifica o descarte pela interface. Com `--review-on-device`, a execução permanece cifrada para inspeção visual e o descarte é feito na própria tela após a conferência. O relatório distingue o término do ensaio do aceite da extração.

## Verificações locais

O build padrão e a suíte JVM passaram. A auditoria do APK confirmou o SHA-256 dos pesos, a ausência de permissões de rede, de PDFs e de assets do OCR. No emulador API 24 passaram dez controles de interface, seletor, armazenamento e comparação de evidência. A seleção e o descarte também passaram com os bytes do controle de transporte enviados pelo mesmo pipe USB do ensaio.

O ensaio de atualização usou um registro protegido da bancada em pacote descartável. O SHA-256 do banco permaneceu igual e o artefato legado continuou legível com a mesma chave após a atualização e o descarte de uma nova extração. A atualização de armazenamento usou uma cópia de teste sem os pesos para caber no espaço disponível no emulador. O APK padrão inclui o modelo fixado.

O S25 recebeu o pacote isolado em instalação limpa, em modo avião e com Wi-Fi desligado. A primeira prova com o prompt `exam-extraction/2` processou a página 1 e concluiu a comparação. Nenhuma das 21 medições esperadas foi publicada. O validador apontou formato final inválido e cobertura insuficiente de texto posicionado. Um segundo ensaio com células JSON publicou 10 medições exatas. IDs curtos elevaram a prova para 16 medições exatas, com cinco omissões e zero extras. A nova versão também passou a citar unidades pelo ID da fonte. No primeiro processamento das seis páginas, o protocolo `exam-extraction/3` publicou 19 das 36 medições esperadas, sem extras nem diferenças entre as publicadas. A versão seguinte valida cada linha separadamente e reenvia em blocos menores somente as células sem confirmação, dentro do orçamento por página e documento. [Totais do primeiro ensaio](../validation/results/simplify-exam-v2-page-1-s25-2026-11-22.json) · [Totais das seis páginas](../validation/results/simplify-exam-v3-six-pages-s25-2026-11-22.json)

A comparação direta no S25 com `exam-extraction/4` confirmou as 36 medições nas seis páginas, com zero omissões, extras ou diferenças de valores, unidades, referências e regiões. A referência ambígua prevista permaneceu sinalizada. As seis páginas mantiveram o aviso de texto sem posição suficiente. [Totais e configuração do ensaio direto](../validation/results/simplify-exam-v4-six-pages-s25-2026-11-22.json)

O cancelamento durante a inferência voltou à tela inicial, encerrou o processo privado e descartou a cópia do laudo no S25. A correção também cobre o cancelamento antes de o serviço confirmar a conexão. Sete testes de ciclo de vida do processo passaram no emulador API 24. [Resultado do cancelamento no S25](../validation/results/simplify-exam-v4-saf-cancellation-s25-2026-11-22.json)

O aceite técnico passou na primeira execução do pacote final instalado do zero no S25, em modo avião com Wi-Fi desligado. O teste selecionou o PDF pelo seletor do Android. As 36 medições das seis páginas coincidiram com os textos e as regiões anotados na lista privada, sem omissões, extras, diferenças ou falhas de inferência. A referência ambígua ficou sinalizada. O teste abriu a prévia com destaque no recorte e na página inteira, confirmou que voltar do seletor de outro PDF mantém o resultado atual e verificou o descarte pela interface. As seis páginas continuaram exibindo o aviso de cobertura de texto posicionado. O aceite cobre esse laudo e inclui a comparação automatizada e os controles de interface. [Relatório agregado do aceite](../validation/results/simplify-exam-v4-saf-acceptance-s25-2026-11-22.json)
