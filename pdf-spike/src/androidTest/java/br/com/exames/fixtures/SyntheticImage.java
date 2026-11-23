package br.com.exames.fixtures;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.zip.CRC32;

/** Pixel patterns and minimal orientation metadata, all synthetic and created in memory. */
public final class SyntheticImage {
    public static byte[] quadrants(boolean jpeg, int orientation) throws Exception {
        Bitmap image = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(image); Paint paint = new Paint();
            paint.setColor(Color.RED); canvas.drawRect(0, 0, 60, 40, paint);
            paint.setColor(Color.GREEN); canvas.drawRect(60, 0, 120, 40, paint);
            paint.setColor(Color.BLUE); canvas.drawRect(0, 40, 60, 80, paint);
            paint.setColor(Color.YELLOW); canvas.drawRect(60, 40, 120, 80, paint);
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            image.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 100, data);
            return orientation(data.toByteArray(), jpeg, orientation);
        } finally { image.recycle(); }
    }
    public static byte[] textImage(int width, int height) {
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(); paint.setColor(Color.BLACK); paint.setTextSize(42);
            canvas.drawText("Glicose 82 mg/dL", 20, Math.min(65, height - 5), paint);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            image.compress(Bitmap.CompressFormat.PNG, 100, output); return output.toByteArray();
        } finally { image.recycle(); }
    }
    private static byte[] orientation(byte[] image, boolean jpeg, int orientation) throws Exception {
        ByteArrayOutputStream metadata = new ByteArrayOutputStream(); DataOutputStream t = new DataOutputStream(metadata);
        t.writeBytes("MM"); t.writeShort(42); t.writeInt(8); t.writeShort(1);
        t.writeShort(0x112); t.writeShort(3); t.writeInt(1); t.writeShort(orientation); t.writeShort(0); t.writeInt(0);
        byte[] tiff = metadata.toByteArray(); ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (jpeg) {
            out.write(image, 0, 2); DataOutputStream segment = new DataOutputStream(out);
            segment.writeShort(0xffe1); segment.writeShort(2 + 6 + tiff.length);
            segment.write(new byte[] {69, 120, 105, 102, 0, 0}); segment.write(tiff);
            out.write(image, 2, image.length - 2);
        } else {
            out.write(image, 0, 33); DataOutputStream chunk = new DataOutputStream(out);
            byte[] type = new byte[] {101, 88, 73, 102};
            chunk.writeInt(tiff.length); chunk.write(type); chunk.write(tiff);
            CRC32 crc = new CRC32(); crc.update(type); crc.update(tiff); chunk.writeInt((int) crc.getValue());
            out.write(image, 33, image.length - 33);
        }
        return out.toByteArray();
    }
}
