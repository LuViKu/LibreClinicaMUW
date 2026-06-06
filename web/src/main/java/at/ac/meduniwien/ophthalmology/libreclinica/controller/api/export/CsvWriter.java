/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Phase E.6 — RFC 4180 CSV writer with UTF-8 BOM + CRLF line
 * endings.
 *
 * <p>Why the BOM: Microsoft Excel on Windows interprets a UTF-8 CSV
 * <em>without</em> a BOM as CP1252, which mangles every umlaut in
 * the discrepancy-export. The three-byte EF BB BF preamble fixes
 * that without breaking macOS Numbers or LibreOffice Calc (both
 * accept the BOM silently).
 *
 * <p>Why CRLF: RFC 4180 mandates it. Excel-on-Windows refuses LF-
 * only CSVs in some legacy configurations; CRLF is portable.
 *
 * <p>Why RFC 4180 quoting (not whatever-Excel-likes-this-week): the
 * sponsor / inspector hand-off may be ingested by R, Python pandas,
 * or a custom parser. RFC 4180 is the lowest-common-denominator
 * contract every CSV parser understands. The rule is simple: wrap
 * any field that contains {@code ,} or {@code "} or {@code \r} or
 * {@code \n} in double quotes, and double any embedded double-quote.
 *
 * <p>The writer streams to an {@link OutputStream} so callers can
 * pipe directly into {@code HttpServletResponse.getOutputStream()}.
 * For tests there's a convenience {@link #toByteArray} that returns
 * the full payload in memory.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 *   try (CsvWriter w = new CsvWriter(response.getOutputStream())) {
 *       w.writeHeader();             // BOM is emitted here, exactly once
 *       w.writeRow("id", "name");
 *       w.writeRow("1",  "O'Brien, Mary");  // → "1","O'Brien, Mary"\r\n
 *   }
 * }</pre>
 */
public final class CsvWriter implements AutoCloseable {

    /** UTF-8 BOM (EF BB BF). Emitted by {@link #writeHeader} once. */
    public static final byte[] UTF8_BOM = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    /** RFC 4180 line terminator. */
    public static final String CRLF = "\r\n";

    private final OutputStream out;
    private final Writer writer;
    private boolean headerEmitted;

    public CsvWriter(OutputStream out) {
        this.out = out;
        this.writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    }

    /**
     * Emit the UTF-8 BOM. Call exactly once, before any
     * {@link #writeRow} call, so Excel-on-Windows treats subsequent
     * bytes as UTF-8 rather than CP1252.
     *
     * <p>Calling twice is a no-op (defensive — the second invocation
     * is silently ignored rather than producing an invalid double-
     * BOM stream).
     */
    public void writeHeader() throws IOException {
        if (headerEmitted) return;
        // Flush writer first so the BOM lands at byte 0 even if the
        // caller has somehow already pushed a UTF-8 char through the
        // writer wrapper. In normal usage the writer hasn't been
        // touched yet so this is a no-op.
        writer.flush();
        out.write(UTF8_BOM);
        out.flush();
        headerEmitted = true;
    }

    /** Convenience overload for a row supplied as varargs. */
    public void writeRow(String... cells) throws IOException {
        writeRow(nullSafe(cells));
    }

    /**
     * Write one CSV row. Per RFC 4180 each cell is double-quoted
     * only when it contains a special character; pre-quoted strings
     * are not detected — pass the raw cell value, not the quoted
     * form.
     */
    public void writeRow(List<String> cells) throws IOException {
        if (cells == null || cells.isEmpty()) {
            writer.write(CRLF);
            return;
        }
        boolean first = true;
        for (String raw : cells) {
            if (!first) writer.write(',');
            first = false;
            writer.write(encode(raw));
        }
        writer.write(CRLF);
    }

    @Override
    public void close() throws IOException {
        writer.flush();
        // The writer wraps the OutputStream — closing the writer
        // closes the underlying stream, which is what the Servlet
        // contract expects.
        writer.close();
    }

    /* ------------------------------------------------------------------ */
    /* Test convenience                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Render a header + rows into a UTF-8 byte array. Used by tests
     * and ad-hoc callers that don't have a streaming consumer.
     */
    public static byte[] toByteArray(List<String> header, List<List<String>> rows) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (CsvWriter w = new CsvWriter(buf)) {
            w.writeHeader();
            if (header != null && !header.isEmpty()) w.writeRow(header);
            if (rows != null) {
                for (List<String> r : rows) w.writeRow(r);
            }
        }
        return buf.toByteArray();
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Encode a single cell per RFC 4180. A {@code null} cell becomes
     * the empty string (not the literal {@code "null"}).
     */
    static String encode(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        boolean needsQuoting = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ',' || c == '"' || c == '\r' || c == '\n') {
                needsQuoting = true;
                break;
            }
        }
        if (!needsQuoting) return raw;
        StringBuilder sb = new StringBuilder(raw.length() + 4);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') sb.append('"').append('"');
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    private static List<String> nullSafe(String[] cells) {
        return cells == null ? List.of() : List.of(nullsToEmpty(cells));
    }

    private static String[] nullsToEmpty(String[] in) {
        String[] out = new String[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[i] == null ? "" : in[i];
        return out;
    }
}
