# Extração privada de documentos

A importação, leitura e OCR são adaptadores disponíveis por Hilt, ligados à UI
e ao processamento recuperável por página. Não há diagnóstico nem
validação clínica. Somente documentos sintéticos foram usados nos ensaios.

## PDF e coordenadas

`PdfPreviewRenderer.open(documentId)` recebe bytes do store autenticado, com
SHA-256 confrontado com o documento protegido. Não aceita URI externa. O módulo
Java `:pdf-local` encapsula PDFium 8035 com o JNI corrigido; tipos da biblioteca
não aparecem na API pública do leitor Kotlin. Texto bruto, índices/IDs de spans,
regiões e metadados do raster preservam a fonte. Região ausente permanece null.

Cada sessão permite uma página ativa. `PdfPageSnapshot.bitmap` já está orientado
pelo PDFium; as caixas usam os pixels desse mesmo bitmap com origem superior
esquerda. `PdfRasterTransform` registra dimensões orientadas em pontos, dimensões
em pixels, escalas e rotação PDF original. Não aplicar a rotação novamente às
caixas ou ao bitmap. Dimensões em pontos são as inteiras expostas pelo binding;
a posição dos spans vem diretamente de seu mapeamento nativo page-to-device.

O leitor tem ownership dos bytes autenticados e os zera no fechamento ou em
falha/cancelamento antes da abertura. Bitmap/página/documento são fechados em
`use`/`finally`. Fechamento JNI é bloqueante e deve ocorrer no dispatcher IO; a
sessão oferece `closeAsync`. Isso não prova interrupção imediata de chamada JNI.

## Imagens e OCR

`PrivatePageLoader.load(id, page)` usa os mesmos arquivos protegidos, valida
hash/MIME, decodifica JPEG/PNG e entrega bitmap/texto com uma única página ativa
por loader singleton. O chamador deve fechar `PrivateDocumentPage` para liberar a
imagem e a fila. Cancelamento na fila não remove ownership de outro chamador;
falha durante leitura libera os recursos antes de permitir a próxima página.

A imagem é inspecionada com `inJustDecodeBounds`, recebe `inSampleSize` antes da
alocação e é orientada antes de OCR/prévia. Os oito valores EXIF (incluindo
espelhamentos) são suportados; metadados inválidos/concorrentes são rejeitados.
`ImageTransform` registra dimensões codificadas, amostra, tamanho final e a tag
original. O buffer codificado é zerado após decodificar; o bitmap fica em memória
até seu owner fechar.

O leitor EXIF próprio lê somente a tag numérica Orientation no IFD0 TIFF de APP1
JPEG ou eXIf PNG, com offsets/contagens limitados e ambas as ordens de bytes.
Não decodifica nomes, GPS, XMP ou thumbnails. Foi inspecionado o ExifInterface
1.4.2, mas ele não foi integrado: `DEBUG=Log.isLoggable(...)` pode habilitar
`printAttributes()` com valores de tags. A implementação de orientação evita esse
caminho e não adiciona logs. Não é um parser EXIF geral.

`PageTextExtractor` usa texto PDF quando os caracteres imprimíveis têm região
utilizável. Caso contrário executa Tesseract com o modelo português empacotado no mesmo
raster. Se houver texto embutido rejeitado, ele é preservado separadamente com
flag, sem tratá-lo como extração confiável. Existência de texto não comprova
associação correta de resultado/referência; o parser continua necessário.
Imagens usam OCR. Confiança desconhecida fica null, inclusive após OCR real.

O resultado também oferece `SourceFragment`: palavras PDF agrupadas por limites
literais/geometria (sem alterar decimais/comparadores) e palavras OCR com suas próprias caixas. O
[parser dos layouts internos](BIOCHEMISTRY_PARSER.md) usa esses fragmentos;
não precisa receber gabarito nem inferir espaços da largura visível dos glifos.

Uma chamada nativa de OCR pode continuar após cancelamento de coroutine. O
adaptador aguarda sua conclusão antes de liberar o bitmap/instância e descarta
o resultado quando o chamador foi cancelado. Isso preserva ownership; não é
cancelamento nativo imediato nem timeout de processo. Workers, barreira de
exclusão e checkpoints protegem a publicação. Pins, licenças e remoção de
telemetria transitiva do runtime anterior estão em [OCR_COMPATIBILITY.md](OCR_COMPATIBILITY.md).

## Limites técnicos iniciais

| Recurso | Limite/controle |
| --- | --- |
| Arquivo original | 20 MiB reais de leitura; metadados SAF não governam aceitação |
| PDF | 20 páginas, verificado antes do raster |
| Raster | 2048 pixels no maior lado; ARGB_8888, até 16 MiB por bitmap |
| Página ativa | uma por sessão PDF e por loader singleton, até fechar o resultado |
| Texto PDF | 100000 caracteres por página, com falha explícita no excesso |

A fonte dos limites é `ExtractionLimits` no adaptador Java; importação, store e
imagem usam `DocumentLimits`. Parâmetros são técnicos, ajustáveis por medição,
sem qualquer interpretação clínica. Arquivos de modelo não usam esses limites.

Rotação pode manter dois bitmaps limitados transitoriamente; leitura/cifra/hash
mantêm buffers limitados em memória. Os limites não são quota de heap total nem
prova de controle de toda alocação interna dos codecs/SDKs nativos. PDFs complexos
ou maliciosos ainda podem exigir isolamento de processo. Não há arquivo
persistente em claro nem fallback em claro após falha de chave.

## Build e dependências

O caminho PDF exige JDK 21. Kotlin 2.3.21 foi necessário porque a stdlib 2.3 do
binding chegou ao classpath do consumidor mesmo com módulo Java. Hilt 2.58 e
Room 2.8.5 foram alinhados; AGP 8.13.2, compileSdk 36 e minSdk 24 permanecem.
O schema Room v1/v2 exportado não mudou. Os testes de persistência/migração foram
reexecutados no S25. A integração LiteRT-LM posterior tem verificação própria em
[BUNDLED_INFERENCE.md](BUNDLED_INFERENCE.md), com ativação na UI ainda pendente.

Referências de compatibilidade: [Android/Kotlin](https://developer.android.com/build/kotlin-support),
[Hilt 2.58 sem exigência de AGP 9](https://github.com/google/dagger/releases/tag/dagger-2.58),
[Room](https://developer.android.com/jetpack/androidx/releases/room).

Preparar o AAR corrigido conforme [PDF_COMPATIBILITY.md](PDF_COMPATIBILITY.md),
depois executar `python3 tools/pdf/stage_core.py`. O staging valida hashes de
AAR, patch e locks e publica em `build/pdfium-repository/`, ignorado pelo Git.
Esse grupo Maven é exclusivo do diretório local; não tem fallback remoto. Rodar
o staging novamente após `clean`, antes de compilar. O app não baixa nada.

```bash
python3 tools/pdf/stage_core.py
./gradlew :app:assembleDebug test :data-local:connectedDebugAndroidTest
```

Os testes compartilham geradores em `:validation-fixtures`, dependência somente
de testes. Nenhuma fixture é empacotada no aplicativo de produção. Os detalhes
de resultados reais por etapa estão em [VALIDACAO.md](VALIDACAO.md).
