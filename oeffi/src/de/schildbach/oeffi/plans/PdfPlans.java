/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.oeffi.plans;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;

/**
 * Renders imported PDF plans into bitmaps, so they can be shown by {@link ScrollImageView}, just like the plans
 * that are delivered as PNG images.
 */
public final class PdfPlans {
    /** Width a page is rendered at, in pixels. */
    private static final int TARGET_WIDTH = 1600;
    /** A page is not rendered narrower than this, in pixels. */
    private static final int MIN_WIDTH = 700;
    /** Maximum scale factor to avoid excessively large bitmaps for small pages. */
    private static final float MAX_SCALE = 4f;
    /**
     * Upper bound of pixels of a rendered plan, to keep memory usage sane. PdfRenderer can only render into
     * ARGB_8888 bitmaps, i.e. four bytes per pixel.
     */
    private static final long MAX_TOTAL_PIXELS = 6L * 1000 * 1000;

    public static final class RenderedPlan {
        public final Bitmap bitmap;
        public final int pagesShown;
        public final int pagesTotal;

        protected RenderedPlan(final Bitmap bitmap, final int pagesShown, final int pagesTotal) {
            this.bitmap = bitmap;
            this.pagesShown = pagesShown;
            this.pagesTotal = pagesTotal;
        }

        public boolean isTruncated() {
            return pagesShown < pagesTotal;
        }
    }

    private PdfPlans() {
    }

    /**
     * Render all pages of the given PDF file into a single bitmap, stacking the pages vertically.
     * <p>
     * If the document is too large to fit into memory, the scale is reduced and/or only the first pages are
     * rendered.
     */
    public static RenderedPlan renderPlan(final File file) throws IOException {
        try (final ParcelFileDescriptor fileDescriptor = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY);
                final PdfRenderer renderer = new PdfRenderer(fileDescriptor)) {
            final int numPages = renderer.getPageCount();
            if (numPages == 0)
                throw new IOException("PDF has no pages: " + file);

            final int[] pageWidths = new int[numPages];
            final int[] pageHeights = new int[numPages];
            for (int i = 0; i < numPages; i++) {
                try (final PdfRenderer.Page page = renderer.openPage(i)) {
                    pageWidths[i] = Math.max(1, page.getWidth());
                    pageHeights[i] = Math.max(1, page.getHeight());
                }
            }

            final Layout layout = computeLayout(pageWidths, pageHeights);
            final int numPagesShown = layout.pagesShown;
            final int[] widths = layout.widths;
            final int[] heights = layout.heights;
            final int totalWidth = layout.totalWidth;
            final int totalHeight = layout.totalHeight;

            final Bitmap bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888);
            try {
                final Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.WHITE);

                int y = 0;
                for (int i = 0; i < numPagesShown; i++) {
                    try (final PdfRenderer.Page page = renderer.openPage(i)) {
                        page.render(bitmap, new Rect(0, y, widths[i], y + heights[i]), null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                    y += heights[i];
                }
            } catch (final RuntimeException | OutOfMemoryError x) {
                bitmap.recycle();
                throw x;
            }

            return new RenderedPlan(bitmap, numPagesShown, numPages);
        }
    }

    /**
     * Render the first page of the given PDF file as a thumbnail.
     */
    public static Bitmap renderThumbnail(final File file, final int width) throws IOException {
        try (final ParcelFileDescriptor fileDescriptor = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY);
                final PdfRenderer renderer = new PdfRenderer(fileDescriptor)) {
            if (renderer.getPageCount() == 0)
                throw new IOException("PDF has no pages: " + file);

            try (final PdfRenderer.Page page = renderer.openPage(0)) {
                final float scale = (float) width / page.getWidth();
                final int pageWidth = Math.max(1, Math.round(page.getWidth() * scale));
                final int pageHeight = Math.max(1, Math.round(page.getHeight() * scale));

                final Bitmap bitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888);
                try {
                    final Canvas canvas = new Canvas(bitmap);
                    canvas.drawColor(Color.WHITE);
                    page.render(bitmap, new Rect(0, 0, pageWidth, pageHeight), null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } catch (final RuntimeException | OutOfMemoryError x) {
                    bitmap.recycle();
                    throw x;
                }

                return bitmap;
            }
        }
    }

    /**
     * Size of the pages to render, in pixels. Pages that don't fit into memory are dropped from the end.
     */
    static final class Layout {
        final int pagesShown;
        final int[] widths;
        final int[] heights;
        final int totalWidth;
        final int totalHeight;

        Layout(final int pagesShown, final int[] widths, final int[] heights, final int totalWidth,
                final int totalHeight) {
            this.pagesShown = pagesShown;
            this.widths = widths;
            this.heights = heights;
            this.totalWidth = totalWidth;
            this.totalHeight = totalHeight;
        }
    }

    static Layout computeLayout(final int[] pageWidths, final int[] pageHeights) {
        final int numPages = pageWidths.length;
        int maxPageWidth = 1;
        for (final int pageWidth : pageWidths)
            maxPageWidth = Math.max(maxPageWidth, pageWidth);

        float scale = Math.min((float) TARGET_WIDTH / maxPageWidth, MAX_SCALE);
        int pagesShown = numPages;
        while (pagesShown > 1 && totalPixels(pageWidths, pageHeights, pagesShown, scale) > MAX_TOTAL_PIXELS) {
            if (maxPageWidth * scale > MIN_WIDTH)
                scale = Math.max((float) MIN_WIDTH / maxPageWidth, scale * 0.8f);
            else
                pagesShown--;
        }
        final long pixels = totalPixels(pageWidths, pageHeights, pagesShown, scale);
        if (pixels > MAX_TOTAL_PIXELS)
            scale *= Math.sqrt((double) MAX_TOTAL_PIXELS / pixels);

        final int[] widths = new int[pagesShown];
        final int[] heights = new int[pagesShown];
        int totalWidth = 0;
        int totalHeight = 0;
        for (int i = 0; i < pagesShown; i++) {
            widths[i] = Math.max(1, Math.round(pageWidths[i] * scale));
            heights[i] = Math.max(1, Math.round(pageHeights[i] * scale));
            totalWidth = Math.max(totalWidth, widths[i]);
            totalHeight += heights[i];
        }

        return new Layout(pagesShown, widths, heights, totalWidth, totalHeight);
    }

    private static long totalPixels(final int[] pageWidths, final int[] pageHeights, final int numPages,
            final float scale) {
        long pixels = 0;
        for (int i = 0; i < numPages; i++)
            pixels += Math.round(pageWidths[i] * scale) * (long) Math.round(pageHeights[i] * scale);
        return pixels;
    }
}
