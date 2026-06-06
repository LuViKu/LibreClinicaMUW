/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.List;

/**
 * Phase E.6 {@code discrepancy-full} — RFC 4180 CSV builder for the
 * {@code /api/v1/discrepancies/export.csv} endpoint.
 *
 * <p>Mirrors the legacy {@code DiscrepancyNoteOutputServlet} CSV
 * format (one row per parent note, no inline child rows) so existing
 * sponsor/inspector tooling that consumes those exports continues to
 * parse the SPA-era files without changes.
 *
 * <p>RFC 4180 conformance:
 * <ul>
 *   <li>CRLF line terminator.</li>
 *   <li>Fields containing comma / double-quote / CRLF are
 *       double-quoted; embedded double-quotes are escaped as
 *       {@code ""}.</li>
 *   <li>UTF-8 throughout (no BOM — Excel-Mac historically chokes on
 *       BOMs in CSV; tooling consuming this endpoint should specify
 *       UTF-8 explicitly).</li>
 * </ul>
 */
public final class DiscrepancyExportCsv {

    private static final String CRLF = "\r\n";

    private static final String[] HEADERS = {
            "id",
            "type",
            "status",
            "subjectId",
            "itemOid",
            "description",
            "assignedTo",
            "daysOpen",
            "lastActivityAt"
    };

    private DiscrepancyExportCsv() {}

    /**
     * Serialise a list of {@link DiscrepancyNoteDto} to CSV.
     *
     * <p>The {@code thread} field is intentionally not exported here —
     * the CSV format is parent-only per the legacy servlet's contract.
     * Use the {@code /thread} sibling endpoint for thread hydration.
     */
    public static String render(List<DiscrepancyNoteDto> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, HEADERS);
        for (DiscrepancyNoteDto r : rows) {
            appendRow(sb, new String[] {
                    r.id(),
                    r.type(),
                    r.status(),
                    r.subjectId(),
                    r.itemOid(),
                    r.description(),
                    r.assignedTo() == null ? "" : r.assignedTo(),
                    Integer.toString(r.daysOpen()),
                    r.lastActivityAt()
            });
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, String[] fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(quoteIfNeeded(fields[i]));
        }
        sb.append(CRLF);
    }

    /** RFC 4180 §2.6 — quote when field contains comma / CRLF / dquote. */
    static String quoteIfNeeded(String s) {
        if (s == null) return "";
        boolean needsQuoting = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',' || c == '"' || c == '\r' || c == '\n') {
                needsQuoting = true;
                break;
            }
        }
        if (!needsQuoting) return s;
        StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append('"');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Build a Content-Disposition filename for the export. Includes
     * the study OID + an ISO-8601 date so downloads land with
     * regulatorily-traceable filenames.
     */
    public static String filenameFor(String studyOid, String isoDate) {
        String oid = (studyOid == null || studyOid.isBlank()) ? "study" : sanitize(studyOid);
        String date = (isoDate == null || isoDate.isBlank()) ? "unknown-date" : sanitize(isoDate);
        return "discrepancies-" + oid + "-" + date + ".csv";
    }

    private static String sanitize(String s) {
        // Conservative: strip everything that isn't ASCII alnum / dash /
        // underscore / dot — keeps filenames safe across OSes without
        // pulling in a URL-encoder. Caller passes study OID + ISO-8601
        // date, both of which only contain safe chars in practice.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
