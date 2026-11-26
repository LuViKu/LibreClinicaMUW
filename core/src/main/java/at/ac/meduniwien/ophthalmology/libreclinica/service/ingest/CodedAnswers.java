/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResponseType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ResponseOptionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ResponseSetBean;

/**
 * The answers a CRF item accepts, read the way data entry reads them.
 *
 * <p>A "performed" binding writes its value into a CRF item without anybody
 * typing it. On a coded item (single-select, radio, checkbox, multi-select)
 * only the item's own answer codes are values; anything else is stored as it
 * is, shows no selection on the form and exports as a code no codelist knows.
 * HealthAEye's "performed" items code Yes as {@code Y}, and the catalogue's
 * default {@code 1} would have been written into every one of them. So the
 * binding endpoint refuses such a value, the ticker refuses to write one, and
 * the catalogue panel offers the item's answers instead of a guess.
 *
 * <p>The options are parsed by {@link ResponseSetBean#setOptions}, the parser
 * data entry uses, so a value accepted here is one a person could have picked.
 * A free-text item accepts any value, as before.
 */
public final class CodedAnswers {

    private CodedAnswers() {}

    /** Response types whose value must be one of the options. */
    private static final Set<Integer> CODED_TYPES = Set.of(
            ResponseType.CHECKBOX.getId(), ResponseType.RADIO.getId(),
            ResponseType.SELECT.getId(), ResponseType.SELECTMULTI.getId());

    /** One answer: the code stored in {@code item_data.value}, and its label. */
    public record Option(String value, String text) {}

    /**
     * One response set of an item.
     *
     * @param responseType the response type's name, e.g. {@code single-select}
     * @param coded        whether a value must be one of {@code options}
     * @param options      the answers, in the form's order; empty when not coded
     */
    public record Answers(String responseType, boolean coded, List<Option> options) {

        /** Whether this set accepts {@code value}: any value when not coded. */
        public boolean accepts(String value) {
            if (!coded) return true;
            if (value == null) return false;
            String v = value.trim();
            return options.stream().anyMatch(o -> o.value().equals(v));
        }

        /** The codes, for messages: {@code Y (Yes), N (No)}. */
        public String describe() {
            List<String> parts = new ArrayList<>();
            for (Option o : options) {
                parts.add(o.value().equals(o.text()) ? o.value() : o.value() + " (" + o.text() + ")");
            }
            return String.join(", ", parts);
        }
    }

    /** Parses one response set as data entry does. */
    public static Answers parse(int responseTypeId, String optionsText, String optionsValues) {
        ResponseType type = ResponseType.get(responseTypeId);
        boolean coded = CODED_TYPES.contains(responseTypeId);
        if (!coded) return new Answers(type.getName(), false, List.of());
        ResponseSetBean set = new ResponseSetBean();
        set.setOptions(optionsText == null ? "" : optionsText, optionsValues == null ? "" : optionsValues);
        List<Option> options = new ArrayList<>();
        for (ResponseOptionBean o : set.getOptions()) {
            if (o.getValue() == null || o.getValue().isEmpty()) continue;
            String text = o.getText();
            options.add(new Option(o.getValue(), text == null || text.isBlank() ? o.getValue() : text));
        }
        return new Answers(type.getName(), true, List.copyOf(options));
    }

    /**
     * The item's response sets over every CRF version that carries it, newest
     * version first, each distinct set once. Empty when no version carries the
     * item.
     */
    public static List<Answers> ofItem(Connection c, String itemOid) throws SQLException {
        Set<Answers> out = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT rs.response_type_id, rs.options_text, rs.options_values "
                        + "  FROM item i "
                        + "  JOIN item_form_metadata ifm ON ifm.item_id = i.item_id "
                        + "  JOIN response_set rs ON rs.response_set_id = ifm.response_set_id "
                        + " WHERE i.oc_oid = ? "
                        + " ORDER BY ifm.crf_version_id DESC")) {
            ps.setString(1, itemOid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(parse(rs.getInt(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Whether {@code value} is an answer of the item in every CRF version that
     * carries it. True for a free-text item, and for an item no version
     * carries: that binding fails differently and is reported as such.
     */
    public static boolean acceptedEverywhere(List<Answers> sets, String value) {
        return sets.stream().allMatch(a -> a.accepts(value));
    }

    /** The first coded set, newest version first; null when none is coded. */
    public static Answers firstCoded(List<Answers> sets) {
        return sets.stream().filter(Answers::coded).findFirst().orElse(null);
    }

    /** The item's answers in one CRF version; null when that version lacks it. */
    public static Answers inVersion(Connection c, int crfVersionId, int itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT rs.response_type_id, rs.options_text, rs.options_values "
                        + "  FROM item_form_metadata ifm "
                        + "  JOIN response_set rs ON rs.response_set_id = ifm.response_set_id "
                        + " WHERE ifm.crf_version_id = ? AND ifm.item_id = ? "
                        + " LIMIT 1")) {
            ps.setInt(1, crfVersionId);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? parse(rs.getInt(1), rs.getString(2), rs.getString(3)) : null;
            }
        }
    }

    /** The item's answers on the CRF version a started form uses. */
    public static Answers onEventCrf(Connection c, int eventCrfId, int itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT rs.response_type_id, rs.options_text, rs.options_values "
                        + "  FROM event_crf ec "
                        + "  JOIN item_form_metadata ifm ON ifm.crf_version_id = ec.crf_version_id "
                        + "                             AND ifm.item_id = ? "
                        + "  JOIN response_set rs ON rs.response_set_id = ifm.response_set_id "
                        + " WHERE ec.event_crf_id = ? "
                        + " LIMIT 1")) {
            ps.setInt(1, itemId);
            ps.setInt(2, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? parse(rs.getInt(1), rs.getString(2), rs.getString(3)) : null;
            }
        }
    }
}
