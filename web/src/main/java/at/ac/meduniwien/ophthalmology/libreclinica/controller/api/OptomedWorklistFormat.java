/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Optomed Client's worklist text file, byte for byte.
 *
 * <p>The Optomed Lumo cannot join a WPA2-Enterprise network and the
 * institution permits no other WLAN, so the camera never reaches the DICOM
 * worklist the sidecar serves. What it does have is a USB link to a PC
 * running the vendor's Optomed Client, and that Client watches a folder for a
 * file named {@code worklist_optomed_lumo.txt}, imports it, pushes it to the
 * camera, and deletes it. Verified on the real Client on 2026-09-23:
 *
 * <ul>
 *   <li>The file must have exactly that name; any other name is ignored.</li>
 *   <li>An import <strong>replaces</strong> the whole list — the Client's
 *       database held the file's three entries afterwards, not three plus the
 *       six that were there before. So every file is the complete current
 *       list and stale entries fall away on their own. Nothing here deduplicates.</li>
 *   <li>The file is consumed on import, so the folder is empty between drops.</li>
 *   <li>A file that names the same {@code PatientID} twice is <strong>refused
 *       and left in the folder</strong> - no error anywhere, the list simply
 *       never reaches the camera. So this renders one record per subject: a
 *       second visit the same day joins the first record's given-name field
 *       ("Baseline+V1", no spaces). Verified 2026-09-23, the day a subject
 *       with two visits stopped the whole list.</li>
 *   <li>The given-name line is split on whitespace into given and middle
 *       name, and a third word is dropped ("Baseline + V1" came back as
 *       {@code Baseline^+} with the V1 gone). An event label therefore keeps
 *       at most two words on the camera; the join above uses no spaces.</li>
 *   <li>An empty (0-byte) file is imported and clears the camera's list;
 *       {@code O} is accepted as a sex value. Both verified on the camera
 *       itself on 2026-09-23.</li>
 * </ul>
 *
 * <p>The format is taken from the vendor's template
 * ({@code worklist_optomed_lumo.txt}, six records): six lines per record,
 * records separated by one blank line, two blank lines after the last, CRLF
 * line ends, no byte-order mark, ASCII. The Client maps the lines to DICOM
 * tags in its own database — line 3 becomes the given name and line 4 the
 * family name of a {@code PatientName} of the form {@code Family^Given}, and
 * line 2 becomes {@code PatientID}, which is what ends up in every image the
 * camera takes and what the upload task later binds on.
 *
 * <pre>
 * 20260924083000        scheduled start, DICOM DT
 * HAE-002               PatientID  — the subject label
 * Baseline              given name — the event label, so the camera screen says what the visit is
 * HAE-002               family name
 * 19700101              date of birth — a placeholder, see below
 * F                     sex
 * </pre>
 *
 * <p><strong>The date of birth is a placeholder on purpose.</strong> The
 * platform holds real dates of birth and the DICOM worklist hands them to the
 * camera, but this file goes to a shared handheld and into a vendor database
 * on a clinic PC, alongside a pseudonymised label — and a label plus a real
 * date of birth re-identifies. The camera needs a syntactically valid date,
 * not a true one, and the upload task never reads it back. The nginx rule that
 * refuses the DICOM worklist at the edge exists precisely because that
 * worklist carries real dates of birth behind a shared secret; this endpoint
 * is allowed through the proxy on the strength of carrying less.
 *
 * <p>Pure: no I/O, no clock, no configuration, so the contract can be tested
 * byte for byte.
 */
final class OptomedWorklistFormat {

    /** The one filename the Client imports. */
    static final String FILENAME = "worklist_optomed_lumo.txt";

    /** See the class comment: a valid date, deliberately not a real one. */
    static final String PLACEHOLDER_DOB = "19700101";

    /** The template's line end. */
    private static final String CRLF = "\r\n";

    /** The vendor's own sample uses {@code yyyyMMddHHmmss}; a bare date with no time is not shown. */
    private static final DateTimeFormatter TM = DateTimeFormatter.ofPattern("HHmmss");

    /** Longest value the camera screen shows without trouble; the DICOM LO limit is 64 anyway. */
    private static final int MAX_FIELD = 64;

    private OptomedWorklistFormat() {}

    /**
     * Render the complete list.
     *
     * @param visits the open visits, already scoped and ordered by the caller
     * @return the file's bytes — ASCII, CRLF, no BOM; empty when there are no
     *         visits, which the Client's replace semantics turn into an empty
     *         camera worklist
     */
    static byte[] render(List<ScheduledVisitQuery.ScheduledVisit> visits) {
        // One record per SUBJECT, not per visit. The Client keys its list by
        // PatientID and refuses - silently, by leaving the file where it is -
        // any file that names the same id twice. Found on 2026-09-23 when a
        // subject with two visits that day (Baseline + V1) stopped the whole
        // list from reaching the camera. A second visit the same day merges
        // into the first record: its label joins the given-name field, so the
        // photographer still sees both, and the earlier start is kept (the
        // query is ordered, so the first seen is the earlier).
        Map<String, List<ScheduledVisitQuery.ScheduledVisit>> bySubject = new LinkedHashMap<>();
        for (ScheduledVisitQuery.ScheduledVisit v : visits) {
            bySubject.computeIfAbsent(ascii(v.subjectLabel(), "UNKNOWN"), k -> new ArrayList<>()).add(v);
        }
        StringBuilder sb = new StringBuilder(bySubject.size() * 80 + 8);
        boolean first = true;
        for (List<ScheduledVisitQuery.ScheduledVisit> group : bySubject.values()) {
            if (!first) sb.append(CRLF);         // the blank line between records
            first = false;
            appendRecord(sb, group);
        }
        if (!bySubject.isEmpty()) {
            // The template ends its last field with CRLF and then two empty
            // lines: three CRLFs in a row, which is what the Client ingested.
            sb.append(CRLF).append(CRLF);
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** One subject's record, from all of its visits that day (usually one). */
    private static void appendRecord(StringBuilder sb, List<ScheduledVisitQuery.ScheduledVisit> group) {
        ScheduledVisitQuery.ScheduledVisit lead = group.get(0);
        String label = ascii(lead.subjectLabel(), "UNKNOWN");
        // Joined WITHOUT spaces. The Client splits the given-name line on
        // whitespace into given and middle name and drops anything after the
        // second word: "Baseline + V1" came back as Baseline^+ with V1 gone.
        // "Baseline+V1" is one word and survives. (The same rule means an
        // event label keeps at most two words on the camera.)
        StringBuilder given = new StringBuilder();
        for (ScheduledVisitQuery.ScheduledVisit v : group) {
            String e = ascii(v.eventLabel(), "Visit");
            if (given.length() > 0) given.append('+');
            given.append(e);
        }
        sb.append(scheduledStart(lead)).append(CRLF)
          .append(label).append(CRLF)
          .append(ascii(given.toString(), "Visit")).append(CRLF)   // re-capped at MAX_FIELD after the join
          .append(label).append(CRLF)
          .append(PLACEHOLDER_DOB).append(CRLF)
          .append(sex(lead.gender())).append(CRLF);
    }

    /**
     * {@code yyyyMMddHHmmss}. {@link ScheduledVisitQuery.ScheduledVisit#date()}
     * is ISO {@code yyyy-MM-dd}; the time is null when the visit carries a
     * date but no meaningful time, in which case midnight — a visit with no
     * date at all is not scheduled and never reaches this class.
     */
    static String scheduledStart(ScheduledVisitQuery.ScheduledVisit v) {
        String day = v.date() == null ? "19700101" : v.date().replace("-", "");
        LocalTime t = v.time();
        return day + (t == null ? "000000" : t.format(TM));
    }

    /**
     * The same mapping the sidecar applies for {@code PatientSex}
     * ({@code dicom_scp.worklist._sex}): first letter of the stored value,
     * {@code M}/{@code F}/{@code O}. Anything else is {@code O} rather than
     * blank, because the template shows the field as mandatory.
     */
    static String sex(String gender) {
        String g = gender == null ? "" : gender.trim().toLowerCase(Locale.ROOT);
        if (g.startsWith("m")) return "M";
        if (g.startsWith("f")) return "F";
        return "O";
    }

    /**
     * ASCII only, single spaces, at most {@link #MAX_FIELD} characters, never
     * empty. Subject labels are ASCII by convention; event labels are
     * whatever a study designer typed, and the template's encoding is the
     * plain 7-bit kind. A field that comes out empty gets the fallback so the
     * six-line shape survives.
     */
    static String ascii(String s, String fallback) {
        if (s == null) return fallback;
        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n') continue;       // a line break would split the record
            if (Character.isWhitespace(c)) { pendingSpace = out.length() > 0; continue; }
            if (c < 0x20 || c > 0x7E) continue;         // not representable in the template's encoding
            if (pendingSpace) { out.append(' '); pendingSpace = false; }
            out.append(c);
        }
        if (out.length() > MAX_FIELD) out.setLength(MAX_FIELD);
        String r = out.toString().trim();
        return r.isEmpty() ? fallback : r;
    }
}
