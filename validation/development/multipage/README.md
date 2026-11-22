# Laudo sintético multipágina

`source.pdf` é um caso de desenvolvimento criado com campos inventados. `expected.json` define o gabarito por página e bloco. O caso fica separado do corpus de métricas e de suas partições.

Gere a fixture com ReportLab 4.4.9, versão fixada em `tools/corpus/requirements.txt`.

```sh
python tools/corpus/generate_multipage.py /tmp/exames-multipage.pdf
cmp /tmp/exames-multipage.pdf validation/development/multipage/source.pdf
pdfinfo /tmp/exames-multipage.pdf
```

O PDF contém seis páginas A4 com camada de texto e permissões de leitura e cópia. A senha de leitura vazia reproduz o perfil que o PDFium precisa abrir. O original pessoal em `examples/` está fora do Git. O gerador recebe apenas o caminho de saída e o APK de produção não inclui a fixture.
