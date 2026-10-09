/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only filename an upload may carry on a deployment that requires
 * de-identification: {@code <label>_<yyyyMMdd>_<OD|OS>[_<n>].<ext>}.
 *
 * <p>A camera or export tool names a file after its patient; this format names
 * it after the study's own pseudonym, the scan day and the eye and nothing
 * else, so the stored {@code original_filename} (and the name handed to the
 * preprocess sidecar) can never carry a person's name.
 */
public final class NeutralFilename {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final String TAIL = "_(\\d{8})_(OD|OS)(?:_(\\d{1,4}))?\\.(e2e|dcm)";

    private NeutralFilename() {}

    /**
     * True when {@code filename} is the neutral name for {@code label}, with a
     * real calendar day and an extension agreeing with {@code extension}
     * (".e2e" or ".dcm").
     */
    public static boolean matches(String filename, String label, String extension) {
        if (filename == null || label == null || label.isBlank() || extension == null) return false;
        // The label is matched exactly; only the eye and extension are case-blind.
        Matcher m = Pattern.compile("^" + Pattern.quote(label) + "(?i:" + TAIL + ")$").matcher(filename);
        if (!m.matches()) return false;
        try {
            LocalDate.parse(m.group(1), DAY);
        } catch (RuntimeException notADay) {
            return false;
        }
        return ("." + m.group(4).toLowerCase(Locale.ROOT)).equals(extension.toLowerCase(Locale.ROOT));
    }

    /** True when the name has the neutral shape for {@code label}, whichever the extension; used by the stored-data scan. */
    public static boolean matchesLabel(String filename, String label) {
        return matches(filename, label, ".e2e") || matches(filename, label, ".dcm");
    }
}
