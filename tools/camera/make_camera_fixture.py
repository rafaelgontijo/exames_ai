#!/usr/bin/env python3
"""Synthetic page for photographing with the real camera (task 4.5). No patient data; ranges are arbitrary.

Same simple-v1 layout as the corpus (header row, one row per analyte, reference printed in the row), but drawn larger and
high-contrast so a phone can read it from a screen or a printout. Expected values are stated here as input, never derived
from the app's output.
"""
import json
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT, SCALE = 600, 800, 3
FONT = Path("/System/Library/Fonts/Supplemental/Arial.ttf")
ROWS = [  # label, result, unit, reference, expected referential status
    ("Glicose", "90", "mg/dL", "70-99 mg/dL", "WITHIN_RANGE"),
    ("Ureia", "52", "mg/dL", "15-45 mg/dL", "ABOVE_RANGE"),
    ("Creatinina", "0,4", "mg/dL", "0,6-1,3 mg/dL", "BELOW_RANGE"),
    ("Sódio", "140", "mmol/L", "135-145 mmol/L", "WITHIN_RANGE"),
    ("Potássio", "4,2", "mmol/L", "3,5-5,1 mmol/L", "WITHIN_RANGE"),
]


def font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT), size * SCALE)


def render(path: Path) -> None:
    image = Image.new("RGB", (WIDTH * SCALE, HEIGHT * SCALE), "white")
    draw = ImageDraw.Draw(image)
    def text(x, y, value, size, bold=False):
        draw.text((x * SCALE, y * SCALE), value, font=font(size), fill="black", anchor="lt")
    text(25, 25, "DADOS SINTÉTICOS — SEM VALIDADE CLÍNICA", 15)
    text(25, 55, "Layout: simple-v1 | Caso: camera-s25", 11)
    text(25, 130, "Analito                  Resultado           Unidade          Referência", 12)
    for index, (label, result, unit, reference, _) in enumerate(ROWS):
        top = 175 + index * 65
        text(25, top, label, 12); text(175, top, result, 12); text(270, top, unit, 12); text(360, top, reference, 12)
    text(25, 755, "Faixas arbitrárias apenas para testes. Nenhum dado de paciente.", 10)
    image.save(path, "PNG")


if __name__ == "__main__":
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    render(out / "camera-fixture.png")
    (out / "camera-fixture.expected.json").write_text(json.dumps(
        [dict(analyteRaw=l, resultRaw=r, unitRaw=u, referenceRaw=ref, expectedStatus=s) for l, r, u, ref, s in ROWS], ensure_ascii=False, indent=2) + "\n")
    print(out / "camera-fixture.png")
