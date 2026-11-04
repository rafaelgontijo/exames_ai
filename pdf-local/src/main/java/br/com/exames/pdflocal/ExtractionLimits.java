package br.com.exames.pdflocal;

/** Technical starting limits, not clinical thresholds. Shared by PDF, import and image adapters. */
public final class ExtractionLimits {
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    public static final int MAX_PAGES = 20;
    public static final int MAX_RASTER_SIDE = 2048;
    public static final int MAX_PDF_CHARACTERS = 100000;
    private ExtractionLimits() { }
}
