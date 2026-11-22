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


def render_closeup(path: Path) -> None:
    """Single large row for checking the real camera when a full page yields too little OCR text."""
    image = Image.new("RGB", (WIDTH * SCALE, HEIGHT * SCALE), "white")
    draw = ImageDraw.Draw(image)
    bold_path = FONT.with_name("Arial Bold.ttf")
    for x, y, value, size, font_path in (
        (65, 220, "LAUDO SINTÉTICO", 108, bold_path),
        (65, 388, "Somente para teste técnico", 54, FONT),
        (65, 2120, "Valores fictícios", 52, FONT),
    ):
        draw.text((x, y), value, font=ImageFont.truetype(str(font_path), size), fill="black")
    positions = (65, 515, 815, 1100)
    for y, size, row in (
        (800, 72, ("Analito", "Resultado", "Unidade", "Referência")),
        (1000, 76, ("Glicose", "18", "mg/dL", "10-21 mg/dL")),
    ):
        for x, value in zip(positions, row, strict=True):
            draw.text((x, y), value, font=ImageFont.truetype(str(bold_path), size), fill="black")
    image.save(path, "PNG")


if __name__ == "__main__":
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    render(out / "camera-fixture.png")
    render_closeup(out / "camera-fixture-closeup.png")
    (out / "camera-fixture.expected.json").write_text(json.dumps(
        [dict(analyteRaw=l, resultRaw=r, unitRaw=u, referenceRaw=ref, expectedStatus=s) for l, r, u, ref, s in ROWS], ensure_ascii=False, indent=2) + "\n")
    print(out / "camera-fixture.png")
