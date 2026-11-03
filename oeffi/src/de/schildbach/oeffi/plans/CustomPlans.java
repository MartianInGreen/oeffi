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

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import de.schildbach.oeffi.Constants;
import de.schildbach.oeffi.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages user-imported PDF plans ("Fahrpläne").
 * <p>
 * Imported plans are stored as PDF files in the plans directory. Their display names are kept in a local index
 * file, so they appear in the plan picker next to the plans that are provided by the transport networks.
 */
public final class CustomPlans {
    public static final String PDF_EXTENSION = ".pdf";

    private static final String FIELD_SEPARATOR = "|";
    private static final long MAX_FILE_SIZE = 64 * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 100;
    private static final byte[] PDF_MAGIC = { '%', 'P', 'D', 'F' };

    private static final Logger log = LoggerFactory.getLogger(CustomPlans.class);

    public static final class Entry {
        public final String planId;
        public final String name;
        public final long addedAt;

        protected Entry(final String planId, final String name, final long addedAt) {
            this.planId = planId;
            this.name = name;
            this.addedAt = addedAt;
        }
    }

    /** The selected file is not a PDF file. */
    public static class NotPdfException extends IOException {
        public NotPdfException() {
            super("not a PDF file");
        }
    }

    /** The selected file is too large to be imported. */
    public static class TooLargeException extends IOException {
        public TooLargeException() {
            super("file too large");
        }
    }

    private CustomPlans() {
    }

    public static File indexFile(final Context context) {
        return new File(context.getFilesDir(), Constants.PLAN_CUSTOM_INDEX_FILENAME);
    }

    public static File planFile(final Context context, final String planId) {
        return new File(context.getDir(Constants.PLANS_DIR, Context.MODE_PRIVATE), planId + PDF_EXTENSION);
    }

    public static boolean isCustomPlanId(@Nullable final String planId) {
        return planId != null && planId.startsWith(Constants.PLAN_CUSTOM_ID_PREFIX);
    }

    /**
     * Copy the PDF file behind the given uri into the plans directory and add it to the local index.
     *
     * @return the entry of the imported plan
     */
    public static Entry importPlan(final Context context, final Uri uri) throws IOException {
        final ContentResolver contentResolver = context.getContentResolver();

        final String planId = Constants.PLAN_CUSTOM_ID_PREFIX + System.currentTimeMillis();
        final File planFile = planFile(context, planId);
        final File plansDir = planFile.getParentFile();
        if (plansDir != null && !plansDir.exists() && !plansDir.mkdirs())
            throw new IOException("could not create directory " + plansDir);

        try {
            long total = 0;
            try (final InputStream is = contentResolver.openInputStream(uri)) {
                if (is == null)
                    throw new IOException("could not open " + uri);

                try (final OutputStream os = new FileOutputStream(planFile)) {
                    final byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_FILE_SIZE)
                            throw new TooLargeException();
                        os.write(buffer, 0, read);
                    }
                }
            }

            if (!hasPdfMagic(planFile))
                throw new NotPdfException();
        } catch (final IOException | RuntimeException x) {
            planFile.delete();
            throw x;
        }

        final String name = displayName(context, uri);
        final Entry entry = new Entry(planId, name, System.currentTimeMillis());
        final List<Entry> entries = new ArrayList<>(read(context));
        entries.add(0, entry);
        writeIndex(context, entries);

        log.info("Imported plan {} from {} ({} bytes)", name, uri, planFile.length());

        return entry;
    }

    /**
     * Remove an imported plan, i.e. its PDF file and its index entry.
     */
    public static boolean deletePlan(final Context context, final String planId) {
        final boolean fileDeleted = planFile(context, planId).delete();

        final List<Entry> entries = read(context);
        final List<Entry> remaining = new ArrayList<>(entries.size());
        boolean found = false;
        for (final Entry entry : entries) {
            if (entry.planId.equals(planId))
                found = true;
            else
                remaining.add(entry);
        }
        if (found)
            writeIndex(context, remaining);

        return fileDeleted || found;
    }

    /**
     * Read all imported plans, most recently imported first.
     */
    public static List<Entry> read(final Context context) {
        return read(indexFile(context));
    }

    /**
     * Read all imported plans from the given index file, most recently imported first.
     */
    static List<Entry> read(final File indexFile) {
        if (!indexFile.exists())
            return Collections.emptyList();

        final List<Entry> entries = new ArrayList<>();

        try (final BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(indexFile), StandardCharsets.UTF_8))) {
            while (true) {
                String line = reader.readLine();
                if (line == null)
                    break;
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#')
                    continue;

                final int firstSeparator = line.indexOf(FIELD_SEPARATOR);
                if (firstSeparator == -1)
                    continue;
                final int secondSeparator = line.indexOf(FIELD_SEPARATOR, firstSeparator + FIELD_SEPARATOR.length());
                if (secondSeparator == -1)
                    continue;

                final String planId = line.substring(0, firstSeparator).trim();
                final String name = line.substring(firstSeparator + FIELD_SEPARATOR.length(), secondSeparator).trim();
                final long addedAt;
                try {
                    addedAt = Long.parseLong(line.substring(secondSeparator + FIELD_SEPARATOR.length()).trim());
                } catch (final NumberFormatException x) {
                    continue;
                }

                if (isCustomPlanId(planId) && !name.isEmpty())
                    entries.add(new Entry(planId, name, addedAt));
            }
        } catch (final IOException x) {
            log.warn("Could not read " + indexFile, x);
        }

        Collections.sort(entries, (entry1, entry2) -> Long.compare(entry2.addedAt, entry1.addedAt));

        return entries;
    }

    private static void writeIndex(final Context context, final List<Entry> entries) {
        final File indexFile = indexFile(context);

        try (final Writer writer = new OutputStreamWriter(new FileOutputStream(indexFile),
                StandardCharsets.UTF_8)) {
            writer.write("# imported PDF plans\n");
            for (final Entry entry : entries) {
                writer.write(entry.planId);
                writer.write(FIELD_SEPARATOR);
                writer.write(entry.name);
                writer.write(FIELD_SEPARATOR);
                writer.write(Long.toString(entry.addedAt));
                writer.write('\n');
            }
        } catch (final IOException x) {
            log.warn("Could not write " + indexFile, x);
        }
    }

    private static boolean hasPdfMagic(final File file) {
        try (final InputStream is = new FileInputStream(file)) {
            final byte[] magic = new byte[PDF_MAGIC.length];
            int read = 0;
            while (read < magic.length) {
                final int result = is.read(magic, read, magic.length - read);
                if (result == -1)
                    return false;
                read += result;
            }

            for (int i = 0; i < PDF_MAGIC.length; i++)
                if (magic[i] != PDF_MAGIC[i])
                    return false;

            return true;
        } catch (final IOException x) {
            return false;
        }
    }

    private static String displayName(final Context context, final Uri uri) {
        String name = null;

        try (final Cursor cursor = context.getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column != -1)
                    name = cursor.getString(column);
            }
        } catch (final RuntimeException x) {
            log.warn("Could not determine display name of " + uri, x);
        }

        if (name == null)
            name = uri.getLastPathSegment();

        if (name != null) {
            if (name.toLowerCase(Constants.DEFAULT_LOCALE).endsWith(PDF_EXTENSION))
                name = name.substring(0, name.length() - PDF_EXTENSION.length());
            name = name.replace(FIELD_SEPARATOR, "-").replace('\n', ' ').replace('\r', ' ').trim();
            if (name.length() > MAX_NAME_LENGTH)
                name = name.substring(0, MAX_NAME_LENGTH).trim();
        }

        if (name == null || name.isEmpty())
            name = context.getString(R.string.plans_picker_custom_plan_name_fallback);

        return name;
    }
}
