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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CustomPlansTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void readEmptyIndex() throws Exception {
        final File indexFile = temporaryFolder.newFile("plans-custom-index.txt");

        Assert.assertTrue(CustomPlans.read(indexFile).isEmpty());
    }

    @Test
    public void readMissingIndex() throws Exception {
        final File indexFile = new File(temporaryFolder.getRoot(), "does-not-exist.txt");

        Assert.assertTrue(CustomPlans.read(indexFile).isEmpty());
    }

    @Test
    public void readIndexSortedByImportDate() throws Exception {
        final File indexFile = temporaryFolder.newFile("plans-custom-index.txt");
        try (final Writer writer = new OutputStreamWriter(new FileOutputStream(indexFile),
                StandardCharsets.UTF_8)) {
            writer.write("# imported PDF plans\n");
            writer.write("custom-1000|Older plan|1000\n");
            writer.write("\n");
            writer.write("custom-3000|Newest plan|3000\n");
            writer.write("custom-2000|Middle plan|2000\n");
        }

        final List<CustomPlans.Entry> entries = CustomPlans.read(indexFile);

        Assert.assertEquals(3, entries.size());
        Assert.assertEquals("custom-3000", entries.get(0).planId);
        Assert.assertEquals("Newest plan", entries.get(0).name);
        Assert.assertEquals(3000, entries.get(0).addedAt);
        Assert.assertEquals("custom-2000", entries.get(1).planId);
        Assert.assertEquals("custom-1000", entries.get(2).planId);
    }

    @Test
    public void readIndexIgnoresMalformedLines() throws Exception {
        final File indexFile = temporaryFolder.newFile("plans-custom-index.txt");
        try (final Writer writer = new OutputStreamWriter(new FileOutputStream(indexFile),
                StandardCharsets.UTF_8)) {
            writer.write("custom-1000|Plan without timestamp\n");
            writer.write("custom-2000||2000\n");
            writer.write("remote-plan|Remote plan|3000\n");
            writer.write("custom-4000|Plan|not-a-number\n");
            writer.write("custom-5000|Valid plan|5000\n");
        }

        final List<CustomPlans.Entry> entries = CustomPlans.read(indexFile);

        Assert.assertEquals(1, entries.size());
        Assert.assertEquals("custom-5000", entries.get(0).planId);
        Assert.assertEquals("Valid plan", entries.get(0).name);
    }

    @Test
    public void customPlanIds() {
        Assert.assertTrue(CustomPlans.isCustomPlanId("custom-1234"));
        Assert.assertFalse(CustomPlans.isCustomPlanId("bvg"));
        Assert.assertFalse(CustomPlans.isCustomPlanId(null));
    }

    @Test
    public void pdfExtension() {
        Assert.assertEquals(".pdf", CustomPlans.PDF_EXTENSION);
    }
}
