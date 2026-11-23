package br.com.exames.fixtures;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.DeflaterOutputStream;

/** In-memory synthetic fixture: text, scanned and /Rotate 90 pages in one PDF. */
public final class SyntheticPdf {
    public static byte[] bytes(String value) { return value.getBytes(StandardCharsets.ISO_8859_1); }
    public static byte[] hybrid() throws Exception {
        Bitmap image = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try {
            Canvas canvas = new Canvas(image);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(); paint.setColor(Color.BLACK); paint.setTextSize(16);
            canvas.drawText("SYNTHETIC RASTER", 10, 30, paint);
            canvas.drawText("Glicose | 82 | mg/dL", 10, 60, paint);
            try (DeflaterOutputStream output = new DeflaterOutputStream(compressed)) {
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getPixel(x, y);
                    output.write(Color.red(pixel)); output.write(Color.green(pixel)); output.write(Color.blue(pixel));
                }
            }
        } finally { image.recycle(); }
        List<byte[]> objects = new ArrayList<>();
        objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
        objects.add(bytes("<< /Type /Pages /Count 3 /Kids [5 0 R 7 0 R 9 0 R] >>"));
        objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        objects.add(stream("/Type /XObject /Subtype /Image /Width 300 /Height 100 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode", compressed.toByteArray()));
        objects.add(page(6, 0));
        objects.add(stream("", bytes("BT /F1 12 Tf 30 300 Td (SYNTHETIC-PDF-PRIVATE-MARKER) Tj 0 -30 Td (Glicose | 82 | mg/dL | 70 a 99) Tj 0 -30 Td (Sódio | 140 | mmol/L | 135 a 145) Tj ET")));
        objects.add(page(8, 0));
        objects.add(stream("", bytes("q 270 0 0 90 15 200 cm /Im1 Do Q")));
        objects.add(page(10, 90));
        objects.add(stream("", bytes("BT /F1 12 Tf 30 300 Td (Potássio | 4,2 | mmol/L | 3,5 a 5,0) Tj ET")));
        return finish(objects);
    }
    public static byte[] pages(int count, int width, int height) throws Exception {
        List<byte[]> objects = new ArrayList<>();
        objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < count; i++) kids.append(4 + i * 2).append(" 0 R ");
        objects.add(bytes("<< /Type /Pages /Count " + count + " /Kids [" + kids + "] >>"));
        objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"));
        for (int i = 0; i < count; i++) {
            objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + width + " " + height + "] /Resources << /Font << /F1 3 0 R >> >> /Contents " + (5 + i * 2) + " 0 R >>"));
            objects.add(stream("", bytes("BT /F1 12 Tf 30 60 Td (SYNTHETIC LIMIT) Tj ET")));
        }
        return finish(objects);
    }
    /** Same two internal column layouts; arbitrary numbers, no clinical ranges. */
    public static byte[] biochemistry(boolean footnote) throws Exception {
        return biochemistry(footnote, 1);
    }
    public static byte[] biochemistry(boolean footnote, int count) throws Exception {
        return biochemistry(footnote, count, 0);
    }
    public static byte[] biochemistry(boolean footnote, int count, int brokenPage) throws Exception {
        if (count < 1 || count > 20) throw new IllegalArgumentException("SYNTHETIC_PAGE_COUNT");
        if (brokenPage < 0 || brokenPage > count) throw new IllegalArgumentException("SYNTHETIC_BROKEN_PAGE");
        StringBuilder content = new StringBuilder();
        text(content, 25, 745, "Layout: " + (footnote ? "footnote-v1" : "simple-v1"));
        text(content, 25, 670, "Analito                  Resultado           Unidade          Referência");
        String[] names = {"Glicose", "Uréia", "Creatinina", "Sódio", "Potássio"};
        for (int i = 0; i < names.length; i++) {
            int y = 613 - i * 65;
            String unit = i < 3 ? "mg/dL" : "mmol/L";
            text(content, 25, y, names[i]); text(content, 175, y, "15"); text(content, 270, y, unit);
            text(content, 360, y, footnote ? "Ver nota " + (i + 1) : "10 a 20 " + unit);
            if (footnote) {
                text(content, 25, 224 - i * 30, (i + 1) + ".");
                text(content, 65, 224 - i * 30, "10 a 20 " + unit);
            }
        }
        List<byte[]> objects = new ArrayList<>();
        objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
        List<String> kids = new ArrayList<>();
        for (int i = 0; i < count; i++) kids.add((4 + i * 2) + " 0 R");
        objects.add(bytes("<< /Type /Pages /Count " + count + " /Kids [" + String.join(" ", kids) + "] >>"));
        objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        for (int i = 0; i < count; i++) {
            objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 3 0 R >> >> /Contents " + (5 + i * 2) + " 0 R >>"));
            objects.add(stream("", bytes(i + 1 == brokenPage ? "SYNTHETIC_INVALID_STREAM ??? ???" : content.toString())));
        }
        return finish(objects);
    }
    private static void text(StringBuilder content, int x, int y, String value) {
        content.append("BT /F1 11 Tf ").append(x).append(' ').append(y).append(" Td (")
            .append(value).append(") Tj ET\n");
    }
    private static byte[] finish(List<byte[]> objects) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(bytes("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n"));
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(output.size()); output.write(bytes((i + 1) + " 0 obj\n"));
            output.write(objects.get(i)); output.write(bytes("\nendobj\n"));
        }
        int xref = output.size();
        output.write(bytes("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n"));
        for (int offset : offsets) output.write(bytes(String.format(Locale.ROOT, "%010d 00000 n \n", offset)));
        output.write(bytes("trailer\n<< /Root 1 0 R /Size " + (objects.size() + 1) + " >>\nstartxref\n" + xref + "\n%%EOF\n"));
        return output.toByteArray();
    }
    private static byte[] page(int content, int rotate) {
        return bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Rotate " + rotate + " /Resources << /Font << /F1 3 0 R >> /XObject << /Im1 4 0 R >> >> /Contents " + content + " 0 R >>");
    }
    private static byte[] stream(String attributes, byte[] data) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(bytes("<< " + attributes + " /Length " + data.length + " >>\nstream\n"));
        output.write(data); output.write(bytes("\nendstream")); return output.toByteArray();
    }
}
