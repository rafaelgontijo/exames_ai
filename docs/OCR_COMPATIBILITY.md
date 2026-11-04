# OCR local e distribuição

A configuração atual usa Tesseract4Android 4.9.0, Tesseract 5.5.1 e Leptonica
1.85.0 com `por` de tessdata_fast 4.1.0. Pins, tamanhos, hashes, fontes e licenças
estão em `validation/ocr/model.lock.json`. O runtime anterior ML Kit foi removido:
a árvore efetiva trazia DataTransport/CCT, incompatível com a proibição de
telemetria e clientes de rede do projeto. Resultados históricos com ML Kit não
representam o OCR atual.

O peso de 1.982.756 bytes permanece fora do Git, por padrão em
`../.model-cache/ocr-por/por.traineddata`; é possível indicar
`-PexamesOcrModelPath=/caminho/por.traineddata`. `tools/ocr/stage.py` confere SHA-256
do AAR, peso e avisos, além do alinhamento ELF de 16 KiB nas bibliotecas de 64 bits.
O APK leva o peso sem compressão e as licenças. Nenhum download ocorre no app.
A biblioteca requer API 21; o aplicativo permanece minSdk 24.

Um provider privado inicializa somente o contexto. A preparação copia o modelo
para `noBackupFilesDir/ocr/tessdata` com lock, hash, fsync e rename atômico.
Cada página recebe uma instância nativa nova, LSTM e segmentação automática;
a imagem fica em memória, a depuração e a escrita de imagens estão desativadas.
Não há calibração de confiança: o campo continua nulo. Cancelamento aguarda o
retorno nativo antes de liberar o bitmap; o coordenador impede commits tardios.

A versão do pipeline é `private-page-pipeline/2-tesseract-por`. Reexecutar um
registro antigo com outra configuração exige uma nova execução; seus resultados
originais permanecem imutáveis. Layout ilegível continua falhando explicitamente,
sem flexibilizar associação, contexto ou `NEEDS_REVIEW`.

Os controles em emulador verificaram OCR privado, quatro formatos e dois layouts.
O controle de relatório mostrou, no caso selecionado, 0/5 classificações corretas
e cobertura numérica 2/5. Isso comprova que a bancada expõe erros, não qualidade
suficiente do extrator. O desempenho e a comparação assistida atual no S25 ainda
precisam ser medidos. Nenhum desses testes constitui validação clínica.
