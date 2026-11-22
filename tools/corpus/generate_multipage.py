"""Build the standalone DEVELOPMENT regression using only invented fields."""
import argparse
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.pdfencrypt import StandardEncryption
from reportlab.pdfgen.canvas import Canvas


# Page-local blocks. The independent oracle lives beside the generated PDF.
PAGES = [
    [("Glicose", "18,5", "mg/dL", "10 a 21 mg/dL")],
    [("Glicemia média estimada", "31,2", "mg/dL", "")],
    [("Ureia", "13,5", "mg/dL", "Grupo A: 7 a 11 mg/dL; Grupo B: 12 a 17 mg/dL")],
    [("Creatinina", "2,4", "mg/dL", "")],
    [("Marcador experimental", "42", "U/L", "1 a 3 U/L")],
    [("Potássio", "5,5", "mmol/L", "< 4 mmol/L"), ("Sódio", "11", "mmol/L", "10 a 20 mmol/L")],
]


def generate(output: Path) -> None:
    protection = StandardEncryption("", ownerPassword="development-fixture", strength=128,
                                    canPrint=1, canCopy=1, canModify=0, canAnnotate=0)
    pdf = Canvas(str(output), pagesize=A4, invariant=1, encrypt=protection, pageCompression=1)
    pdf.setTitle("Regressão multipágina sintética")
    for page, rows in enumerate(PAGES, 1):
        pdf.setFont("Helvetica-Bold", 16)
        pdf.drawString(24, 800, "Laudo sintético de desenvolvimento")
        pdf.setFont("Helvetica", 11)
        pdf.drawString(24, 770, "Identificação fictícia: PESSOA-TESTE-ZETA")
        pdf.drawString(24, 746, "Material: amostra simulada")
        pdf.drawString(24, 722, "Método: ensaio fictício")
        def table_row(y, fields):
            for x, field in zip((24, 174, 275, 358), fields, strict=True):
                pdf.drawString(x, y, field)
        table_row(650, ("Analito", "Resultado", "Unidade", "Referência"))
        pdf.setFont("Helvetica", 9)
        for index, row in enumerate(rows):
            table_row(615 - 40 * index, row)
        pdf.drawString(24, 36, f"Página {page} de 6. Valores inventados para teste.")
        pdf.showPage()
    pdf.save()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    generate(parser.parse_args().output)
