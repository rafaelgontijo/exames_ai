#!/usr/bin/env python3
"""Reproducible synthetic documents. Expected answers are input data, never evaluator output."""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import io
import json
from pathlib import Path
import random
import struct
import zipfile

from PIL import Image, ImageDraw, ImageFilter, ImageFont
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.utils import ImageReader

ROOT = Path(__file__).resolve().parents[2]
LOCK = json.loads((Path(__file__).parent / "toolchain.lock.json").read_text())
WIDTH, HEIGHT, SCALE = 600, 800, 2
VARIANTS = (
    ("pdf-text", "PDF_TEXT", "pdf", []),
    ("pdf-scanned", "PDF_SCANNED", "pdf", ["RASTERIZE_2X"]),
    ("png", "PNG", "png", []),
    ("jpeg", "JPEG", "jpg", []),
    ("png-rotated", "PNG", "png", ["ROTATE_CCW_90"]),
    ("jpeg-degraded", "JPEG", "jpg", ["BLUR_1", "JPEG_QUALITY_35", "SEEDED_NOISE"]),
)


def stable_id(*parts: str) -> str:
    digest = hashlib.sha256()
    for part in parts:
        data = part.encode("utf-8")
        digest.update(struct.pack(">I", len(data)))
        digest.update(data)
    return digest.hexdigest()


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode()


def check_toolchain(font: Path) -> None:
    for name, expected in LOCK["dependencies"].items():
        if importlib.metadata.version(name) != expected:
            raise ValueError(f"Install pinned requirements: {name}=={expected}")
    if sha(font.read_bytes()) != LOCK["fontSha256"]:
        raise ValueError("Font hash does not match toolchain.lock.json")
    pdfmetrics.registerFont(TTFont("CorpusSans", str(font)))


def page_definition(source: dict, scenario: dict, layout: str, family: str):
    lines = [("", "", "DADOS SINTÉTICOS — SEM VALIDADE CLÍNICA", 25, 25, 15),
             ("", "", f"Layout: {layout} | Caso: {scenario['id']}", 25, 55, 11),
             ("", "", "Analito                  Resultado           Unidade          Referência", 25, 130, 12)]
    expected = []
    for index, analyte in enumerate(source["analytes"]):
        obs_id = stable_id("observation", family, analyte["id"])
        top = 175 + index * 65
        unit = analyte["unit"]
        ref_unit = "u-incompatível" if scenario["id"] == "unit-mismatch" else unit
        reference = scenario["referenceRaw"]
        reference = None if reference is None else f"{reference} {ref_unit}"
        fields = [("analyteRaw", analyte["label"], 25, top, 12),
                  ("resultRaw", scenario["resultRaw"], 175, top, 12),
                  ("unitRaw", unit, 270, top, 12)]
        if reference is not None:
            if layout == "footnote-v1":
                lines.append(("", "", f"Ver nota {index + 1}", 360, top, 11))
                fields.append(("referenceRaw", reference, 65, 565 + index * 30, 11))
                lines.append(("", "", f"{index + 1}.", 25, 565 + index * 30, 11))
            else:
                fields.append(("referenceRaw", reference, 360, top, 9))
        evidence = []
        for field, text, left, y, size in fields:
            if not text:
                continue
            width = pdfmetrics.stringWidth(text, "CorpusSans", size)
            if left + width + 3 > WIDTH:
                raise ValueError("Fixture exceeds page width")
            region = dict(page=1, left=left, top=y, right=left + width + 3,
                          bottom=y + size + 7, pageWidth=WIDTH, pageHeight=HEIGHT, rotationDegrees=0)
            evidence.append(dict(field=field, raw=text, region=region,
                                 spanId=stable_id(obs_id, field), method="SYNTHETIC",
                                 extractorVersion=LOCK["generatorVersion"], confidence=None))
            lines.append((field, obs_id, text, left, y, size))
        expected.append(dict(observationId=obs_id, analyte=analyte["id"],
                             analyteRaw=analyte["label"], resultRaw=scenario["resultRaw"],
                             unitRaw=unit, referenceRaw=reference,
                             numericValue=scenario["numericValue"], low=scenario["low"], high=scenario["high"],
                             expectedStatus=scenario["expectedStatus"], expectedReason=scenario["expectedReason"],
                             recoverable=scenario["id"] != "unreadable", evidence=evidence))
    lines.append(("", "", "Faixas arbitrárias apenas para testes. Nenhum dado de paciente.", 25, 755, 10))
    return lines, expected


def render_image(lines, font: Path, unreadable: bool) -> Image.Image:
    image = Image.new("RGB", (WIDTH * SCALE, HEIGHT * SCALE), "white")
    draw = ImageDraw.Draw(image)
    for _, _, text, x, y, size in lines:
        draw.text((x * SCALE, y * SCALE), text,
                  font=ImageFont.truetype(str(font), size * SCALE), fill="black", anchor="lt")
    if unreadable:
        for index in range(5):
            y = (175 + index * 65) * SCALE
            draw.rectangle((170 * SCALE, y, 235 * SCALE, y + 22 * SCALE), fill="black")
    return image


def render_pdf(lines, image: Image.Image | None, unreadable: bool) -> bytes:
    buffer = io.BytesIO()
    pdf = canvas.Canvas(buffer, pagesize=(WIDTH, HEIGHT), invariant=1, pageCompression=1)
    pdf.setTitle("Corpus sintético de validação")
    pdf.setAuthor("Exames Offline — synthetic generator")
    if image is not None:
        pdf.drawImage(ImageReader(image), 0, 0, width=WIDTH, height=HEIGHT)
    else:
        for _, _, text, x, top, size in lines:
            pdf.setFont("CorpusSans", size)
            pdf.drawString(x, HEIGHT - top - size, text)
        if unreadable:
            for index in range(5):
                top = 175 + index * 65
                pdf.rect(170, HEIGHT - top - 22, 65, 22, fill=1, stroke=0)
    pdf.showPage()
    pdf.save()
    return buffer.getvalue()


def transform_regions(observations: list, image_format: bool, rotated: bool):
    result = json.loads(json.dumps(observations))
    if not image_format:
        return result
    for observation in result:
        for evidence in observation["evidence"]:
            r = evidence["region"]
            left, top, right, bottom = [r[k] * SCALE for k in ("left", "top", "right", "bottom")]
            w, h = WIDTH * SCALE, HEIGHT * SCALE
            if rotated:
                left, top, right, bottom = top, w - right, bottom, w - left
                w, h = h, w
            r.update(left=left, top=top, right=right, bottom=bottom,
                     pageWidth=w, pageHeight=h, rotationDegrees=90 if rotated else 0)
    return result


def package(directory: Path, manifest: dict, target: Path) -> None:
    names = ["manifest.json"] + [case[key] for case in manifest["cases"]
                                 for key in ("documentPath", "groundTruthPath")]
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(set(names)):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, (directory / name).read_bytes(), compresslevel=9)


def generate(output: Path, font: Path = Path(LOCK["fontDefault"])) -> dict:
    check_toolchain(font)
    if output.exists() and any(output.iterdir()):
        raise ValueError("Output must be a new or empty directory; no existing files are deleted")
    output.mkdir(parents=True, exist_ok=True)
    source = json.loads((ROOT / "validation/corpus/cases.json").read_text())
    manifest = dict(schemaVersion="1.0", corpusVersion=source["corpusVersion"],
                    generatorVersion=LOCK["generatorVersion"], seed=source["seed"],
                    fontSha256=LOCK["fontSha256"], cases=[])
    for index, scenario in enumerate(source["scenarios"]):
        family = stable_id("family", scenario["id"])
        partition = "HELD_OUT" if index % 4 == 0 else "DEVELOPMENT"
        for layout in source["layouts"]:
            lines, expected = page_definition(source, scenario, layout, family)
            base_image = render_image(lines, font, scenario["id"] == "unreadable")
            for variant, fmt, extension, transformations in VARIANTS:
                case_id = stable_id("case", family, layout, variant)
                image = base_image.copy()
                rotated = variant == "png-rotated"
                if rotated:
                    image = image.transpose(Image.Transpose.ROTATE_90)
                if variant == "jpeg-degraded":
                    image = image.filter(ImageFilter.GaussianBlur(1))
                    rng = random.Random(f"{source['seed']}:{case_id}")
                    draw = ImageDraw.Draw(image)
                    for _ in range(150):
                        draw.point((rng.randrange(image.width), rng.randrange(image.height)), fill="gray")
                if fmt.startswith("PDF"):
                    data = render_pdf(lines, image if fmt == "PDF_SCANNED" else None,
                                      scenario["id"] == "unreadable")
                else:
                    buffer = io.BytesIO()
                    image.save(buffer, format=fmt, **({"quality":35 if variant == "jpeg-degraded" else 90,
                               "subsampling":0, "optimize":False} if fmt == "JPEG" else {}))
                    data = buffer.getvalue()
                document_path = f"documents/{case_id}.{extension}"
                truth_path = f"truth/{case_id}.json"
                truth = dict(schemaVersion="1.0", caseId=case_id, version="1",
                             reviewState="REVIEWED", reviewerId="synthetic-fixture-author",
                             reviewedAt="2026-10-21T00:00:00Z",
                             observations=transform_regions(expected, fmt in ("PNG", "JPEG"), rotated))
                truth_data = json_bytes(truth)
                for name, content in ((document_path, data), (truth_path, truth_data)):
                    target = output / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(content)
                manifest["cases"].append(dict(caseId=case_id, familyId=family, origin="SYNTHETIC",
                    format=fmt, partition=partition, layoutId=layout, layoutVersion="1",
                    documentPath=document_path, documentSha256=sha(data), documentBytes=len(data),
                    groundTruthPath=truth_path, groundTruthSha256=sha(truth_data),
                    transformations=transformations, provenance=None))
                image.close()
            base_image.close()
    (output / "manifest.json").write_bytes(json_bytes(manifest))
    package(output, manifest, output / "corpus.zip")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "validation/generated")
    parser.add_argument("--font", type=Path, default=Path(LOCK["fontDefault"]))
    args = parser.parse_args()
    result = generate(args.output, args.font)
    print(f"Generated {len(result['cases'])} synthetic variants: {args.output / 'corpus.zip'}")
