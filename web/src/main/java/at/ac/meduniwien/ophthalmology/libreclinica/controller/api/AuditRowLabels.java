/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an audit row records, read from the row rather than from its type id
 * alone. Pure functions, so the audit view's labelling is testable without a
 * database.
 *
 * <p>Three kinds of row need more than the id:
 * <ul>
 *   <li><b>Ids that were written with two meanings.</b> MUW code wrote 11 for
 *       reopening and for restoring a CRF, which the event_crf trigger uses
 *       for a completed double data entry; it wrote 27 for a reason-for-change
 *       note, which the study_subject trigger uses for a site reassignment;
 *       and it wrote restores of a dismissed file under the dismiss type.
 *       Since 2026-09-27 those writers have types of their own (137-140).
 *       Rows written before are recognised by what they carry and read as the
 *       type they record: {@link #effectiveType}.</li>
 *   <li><b>Failures</b>, whose new value packs the error as
 *       {@code class|message|request id}: {@link #formatFailure}.</li>
 *   <li><b>Rows that name their visit only in their values</b>, as the ingest
 *       rows do with {@code study_event_id=}: {@link #studyEventIdIn}.</li>
 * </ul>
 */
final class AuditRowLabels {

    /** OPERATION_FAILED; job failures are written under it as well. */
    static final int OPERATION_FAILED = 61;
    /** JOB_FAILED, reserved alongside 61. */
    static final int JOB_FAILED = 62;

    private static final Pattern STUDY_EVENT_ID = Pattern.compile("(?:^|;)study_event_id=(\\d+)");

    private AuditRowLabels() {
    }

    /**
     * The type the row records. Equal to {@code typeId} except for rows
     * written under an id that meant something else at the time.
     *
     * @param entityName the row's column marker ({@code audit_log_event.entity_name})
     */
    static int effectiveType(int typeId, String auditTable, String entityName,
                             String oldValue, String newValue) {
        switch (typeId) {
            case 11 -> {
                // The trigger writes 11 with the marker "Status" (4 -> 2); MUW's
                // reopen wrote "date_completed" and its restore "status_id".
                if ("event_crf".equalsIgnoreCase(auditTable)) {
                    if ("date_completed".equalsIgnoreCase(trim(entityName))) {
                        return AuditTypeIds.EVENT_CRF_REOPENED;
                    }
                    if ("status_id".equalsIgnoreCase(trim(entityName))) {
                        return AuditTypeIds.EVENT_CRF_RESTORED;
                    }
                }
            }
            case 27 -> {
                // The trigger writes 27 on study_subject; MUW's reason-for-change
                // row sat on item_data.
                if ("item_data".equalsIgnoreCase(auditTable)) {
                    return AuditTypeIds.ITEM_DATA_REASON_FOR_CHANGE;
                }
            }
            case AuditTypeIds.IMAGE_DISMISS -> {
                if (startsWith(oldValue, "DISMISSED") && startsWith(newValue, "UNBOUND")) {
                    return AuditTypeIds.INGEST_RESTORE;
                }
            }
            default -> {
                // Recorded as what it is.
            }
        }
        return typeId;
    }

    /**
     * The filter bucket. A reason moves a data change into
     * {@code reason-for-change}, but not a row about an ingested file: a
     * dismissal's reason says why a file is not study data, which is no
     * change to study data.
     */
    static String variant(int effectiveType, String auditTable, String reason) {
        if ("ingest_item".equalsIgnoreCase(auditTable)) {
            return AuditApiController.variantForType(effectiveType, null);
        }
        return AuditApiController.variantForType(effectiveType, reason);
    }

    /**
     * A failure's new value, {@code class|message|request id}, as one line:
     * {@code class: message · request id}. Anything not in that shape is
     * returned unchanged.
     */
    static String formatFailure(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // The class never contains the separator and the request id is last;
        // the message between them may contain it.
        int first = raw.indexOf('|');
        if (first < 0) return raw;
        int last = raw.lastIndexOf('|');
        String errorClass = raw.substring(0, first).trim();
        String message = (last == first ? raw.substring(first + 1) : raw.substring(first + 1, last)).trim();
        String requestId = last == first ? "" : raw.substring(last + 1).trim();
        StringBuilder sb = new StringBuilder(errorClass.isEmpty() ? "error" : errorClass);
        if (!message.isEmpty()) sb.append(": ").append(message);
        if (!requestId.isEmpty()) sb.append(" · request ").append(requestId);
        return sb.toString();
    }

    static boolean isFailure(int typeId) {
        return typeId == OPERATION_FAILED || typeId == JOB_FAILED;
    }

    /** The first {@code study_event_id=} among the values, or null. */
    static Integer studyEventIdIn(String... values) {
        for (String v : values) {
            if (v == null) continue;
            Matcher m = STUDY_EVENT_ID.matcher(v);
            if (m.find()) {
                try {
                    int id = Integer.parseInt(m.group(1));
                    if (id > 0) return id;
                } catch (NumberFormatException tooLong) {
                    // Not an id this schema can hold; keep looking.
                }
            }
        }
        return null;
    }

    /**
     * True for an ingest row's entity name that is only a column marker, as
     * written before the file reference was recorded there.
     */
    static boolean isBareMarker(String entityName) {
        String t = trim(entityName);
        return t.isEmpty() || "status".equalsIgnoreCase(t) || "retinal_jobs".equalsIgnoreCase(t);
    }

    /**
     * How a visit is named in the audit view: its definition, and which
     * occurrence of a repeating one.
     */
    static String visitLabel(String definitionName, int ordinal, boolean repeating) {
        String name = trim(definitionName);
        if (name.isEmpty()) return null;
        return repeating && ordinal > 0 ? name + " #" + ordinal : name;
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.trim().toUpperCase(java.util.Locale.ROOT).startsWith(prefix);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
