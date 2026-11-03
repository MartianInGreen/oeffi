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

import org.junit.Assert;
import org.junit.Test;

/** Tests the page layout of rendered PDF plans, i.e. the memory constraints. Pure math, no Android. */
public class PdfPlansTest {
    /** Size of an A4 page in PDF points. */
    private static final int A4_WIDTH = 595;
    private static final int A4_HEIGHT = 842;

    @Test
    public void singlePage() {
        final PdfPlans.Layout layout = PdfPlans.computeLayout(new int[] { A4_WIDTH }, new int[] { A4_HEIGHT });

        Assert.assertEquals(1, layout.pagesShown);
        Assert.assertEquals(1, layout.widths.length);
        Assert.assertEquals(1, layout.heights.length);
        Assert.assertEquals(1600, layout.widths[0]);
        Assert.assertEquals(1600, layout.totalWidth);
        Assert.assertEquals(Math.round(A4_HEIGHT * 1600f / A4_WIDTH), layout.heights[0]);
        Assert.assertEquals(layout.heights[0], layout.totalHeight);
    }

    @Test
    public void twoPagesAreStacked() {
        final PdfPlans.Layout layout = PdfPlans
                .computeLayout(new int[] { A4_WIDTH, A4_WIDTH }, new int[] { A4_HEIGHT, A4_HEIGHT });

        Assert.assertEquals(2, layout.pagesShown);
        Assert.assertEquals(layout.heights[0] * 2, layout.totalHeight);
    }

    @Test
    public void manyPagesAreTruncated() {
        final int numPages = 200;
        final int[] widths = new int[numPages];
        final int[] heights = new int[numPages];
        for (int i = 0; i < numPages; i++) {
            widths[i] = A4_WIDTH;
            heights[i] = A4_HEIGHT;
        }

        final PdfPlans.Layout layout = PdfPlans.computeLayout(widths, heights);

        Assert.assertTrue(layout.pagesShown < numPages);
        Assert.assertTrue(layout.pagesShown > 0);
        Assert.assertEquals(layout.pagesShown, layout.widths.length);
        // a page still has to be readable
        Assert.assertTrue(layout.widths[0] > 500);
        // and the result has to fit into memory
        final long pixels = (long) layout.totalWidth * layout.totalHeight;
        Assert.assertTrue(pixels <= 6L * 1000 * 1000);
    }

    @Test
    public void hugePageIsScaledDown() {
        final PdfPlans.Layout layout = PdfPlans.computeLayout(new int[] { 1000 }, new int[] { 40000 });

        Assert.assertEquals(1, layout.pagesShown);
        final long pixels = (long) layout.totalWidth * layout.totalHeight;
        Assert.assertTrue(pixels <= 6L * 1000 * 1000);
        Assert.assertTrue(layout.totalHeight > layout.totalWidth);
    }

    @Test
    public void smallPageIsScaledUpAtMostFourTimes() {
        final PdfPlans.Layout layout = PdfPlans.computeLayout(new int[] { 100 }, new int[] { 100 });

        Assert.assertEquals(400, layout.widths[0]);
        Assert.assertEquals(400, layout.heights[0]);
    }

    @Test
    public void pagesAreNeverEmpty() {
        final PdfPlans.Layout layout = PdfPlans.computeLayout(new int[] { 1 }, new int[] { 1 });

        Assert.assertEquals(1, layout.pagesShown);
        Assert.assertTrue(layout.widths[0] >= 1);
        Assert.assertTrue(layout.heights[0] >= 1);
    }
}
