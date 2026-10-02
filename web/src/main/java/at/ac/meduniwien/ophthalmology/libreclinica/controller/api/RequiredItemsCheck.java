/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.crfdata.SCDItemMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.SimpleConditionalDisplayService;

/**
 * The required items of an event CRF that are shown and still empty: what
 * legacy data entry refuses to complete a CRF with.
 *
 * <p>Legacy {@code DataEntryServlet} validates every item of the form on the
 * save that marks the CRF complete, and an empty required item fails it
 * ({@code IS_REQUIRED}) when the item is shown. Shown means:
 * <ul>
 *   <li>the item, and its group, are shown by the CRF's metadata
 *       ({@code show_item}, {@code show_group}), or a rule has shown them for
 *       this event CRF ({@code dyn_item_form_metadata},
 *       {@code dyn_item_group_metadata});</li>
 *   <li>for an item with a simple conditional display, its control item holds
 *       the option that shows it
 *       ({@link SimpleConditionalDisplayService#conditionalDisplayToBeShown});
 *       in a repeating group whose rows also hold the control item, the
 *       control item of the same row.</li>
 * </ul>
 * In a repeating group every row is checked, and a group without any row
 * counts as one empty row, which is what the legacy form presents.
 *
 * <p>An empty value whose row carries an active discrepancy note is not
 * missing: legacy lets a field with a note through every check but the
 * always-executed ones ({@code DiscrepancyValidator}), and {@code IS_REQUIRED}
 * is not one of them. That is how a site documents a value it cannot obtain.
 *
 * <p>The check reads the stored values: the SPA saves before it completes.
 */
final class RequiredItemsCheck {

    private RequiredItemsCheck() {}

    /** A required item, or a required item in one row of a repeating group, left empty. */
    record Missing(String key, String label) {}

    private record ItemMeta(int itemId, String oid, String name, String label, int ifmId,
                            boolean required, boolean showItem, Integer groupId,
                            boolean repeating, boolean showGroup) {}

    private static final String ITEMS_SQL =
            "SELECT i.item_id, i.oc_oid, i.name, "
                    + "       COALESCE(NULLIF(TRIM(ifm.left_item_text), ''), i.name) AS label, "
                    + "       ifm.item_form_metadata_id, COALESCE(ifm.required, false), "
                    + "       COALESCE(ifm.show_item, true), igm.item_group_id, "
                    + "       COALESCE(igm.repeating_group, false), COALESCE(igm.show_group, true) "
                    + "  FROM item_form_metadata ifm "
                    + "  JOIN item i ON i.item_id = ifm.item_id "
                    + "  LEFT JOIN section s ON s.section_id = ifm.section_id "
                    + "  LEFT JOIN item_group_metadata igm "
                    + "         ON igm.item_id = ifm.item_id AND igm.crf_version_id = ifm.crf_version_id "
                    + " WHERE ifm.crf_version_id = ? "
                    + " ORDER BY s.ordinal, ifm.ordinal, i.item_id";

    /**
     * Live values: removed rows and removed values do not count. The last
     * column says whether the row carries an active discrepancy note.
     */
    private static final String VALUES_SQL =
            "SELECT d.item_id, COALESCE(d.ordinal, 1), d.value, "
                    + "       EXISTS (SELECT 1 FROM dn_item_data_map m "
                    + "                WHERE m.item_data_id = d.item_data_id AND m.activated = true) "
                    + "  FROM item_data d "
                    + " WHERE d.event_crf_id = ? AND COALESCE(d.deleted, false) = false "
                    + "   AND COALESCE(d.status_id, 1) NOT IN (5, 7)";

    private static final String SCD_SQL =
            "SELECT scd.scd_item_form_metadata_id, scd.control_item_form_metadata_id, "
                    + "       scd.control_item_name, scd.option_value "
                    + "  FROM scd_item_metadata scd "
                    + "  JOIN item_form_metadata ifm ON ifm.item_form_metadata_id = scd.scd_item_form_metadata_id "
                    + " WHERE ifm.crf_version_id = ?";

    private static final String DYN_ITEMS_SQL =
            "SELECT item_form_metadata_id FROM dyn_item_form_metadata "
                    + " WHERE event_crf_id = ? AND show_item = true";

    private static final String DYN_GROUPS_SQL =
            "SELECT item_group_id FROM dyn_item_group_metadata "
                    + " WHERE event_crf_id = ? AND show_group = true";

    /**
     * The required, shown items of {@code ecb} that have no value, in form
     * order. The key is the item OID, or {@code OID[row]} in a repeating group.
     */
    static List<Missing> missing(DataSource dataSource, EventCRFBean ecb) {
        try (Connection c = dataSource.getConnection()) {
            List<ItemMeta> items = items(c, ecb.getCRFVersionId());
            Set<String> noted = new HashSet<>();
            Map<Integer, Map<Integer, String>> values = values(c, ecb.getId(), noted);
            Map<Integer, ItemMeta> itemById = new HashMap<>();
            for (ItemMeta item : items) itemById.putIfAbsent(item.itemId(), item);
            Set<Integer> shownByRule = ids(c, DYN_ITEMS_SQL, ecb.getId());
            Set<Integer> groupsShownByRule = ids(c, DYN_GROUPS_SQL, ecb.getId());
            Map<Integer, List<String[]>> scdByIfm = scd(c, ecb.getCRFVersionId(), items);

            // The rows each repeating group has: the ordinals any of its items holds.
            Map<Integer, TreeSet<Integer>> rowsByGroup = new HashMap<>();
            for (ItemMeta item : items) {
                if (!item.repeating()) continue;
                TreeSet<Integer> rows = rowsByGroup.computeIfAbsent(item.groupId(), k -> new TreeSet<>());
                rows.addAll(values.getOrDefault(item.itemId(), Map.of()).keySet());
            }

            List<Missing> out = new ArrayList<>();
            for (ItemMeta item : items) {
                if (!item.required()) continue;
                if (!item.showItem() && !shownByRule.contains(item.ifmId())) continue;
                if (item.groupId() != null && !item.showGroup()
                        && !groupsShownByRule.contains(item.groupId())) continue;
                Map<Integer, String> stored = values.getOrDefault(item.itemId(), Map.of());
                if (item.repeating()) {
                    TreeSet<Integer> rows = rowsByGroup.get(item.groupId());
                    if (rows == null || rows.isEmpty()) {
                        rows = new TreeSet<>(Set.of(1));
                    }
                    for (int row : rows) {
                        if (missing(item, row, stored, noted)
                                && shownByConditionalDisplay(item, row, scdByIfm, values, itemById)) {
                            out.add(new Missing(EventCrfsApiController.groupRowReasonKey(item.oid(), row),
                                    item.label()));
                        }
                    }
                } else if (missing(item, 1, stored, noted)
                        && shownByConditionalDisplay(item, 1, scdByIfm, values, itemById)) {
                    out.add(new Missing(item.oid(), item.label()));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not check the required items of event_crf " + ecb.getId(), e);
        }
    }

    /** Empty in that row, and no active discrepancy note on the row. */
    private static boolean missing(ItemMeta item, int row, Map<Integer, String> stored, Set<String> noted) {
        return blank(stored.get(row)) && !noted.contains(item.itemId() + ":" + row);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Legacy shows an item with a simple conditional display when its control
     * item holds the option; with several conditions, when any holds. A
     * condition whose control item is not on the form never shows it. A
     * control item in the same repeating group is read in the same row.
     */
    private static boolean shownByConditionalDisplay(ItemMeta item, int row, Map<Integer, List<String[]>> scdByIfm,
                                                     Map<Integer, Map<Integer, String>> values,
                                                     Map<Integer, ItemMeta> itemById) {
        List<String[]> conditions = scdByIfm.get(item.ifmId());
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (String[] condition : conditions) {
            int controlItemId = Integer.parseInt(condition[0]);
            ItemMeta control = itemById.get(controlItemId);
            boolean sameRows = item.repeating() && control != null && control.repeating()
                    && item.groupId().equals(control.groupId());
            String chosen = values.getOrDefault(controlItemId, Map.of()).get(sameRows ? row : 1);
            SCDItemMetadataBean cd = new SCDItemMetadataBean();
            cd.setOptionValue(condition[1]);
            if (SimpleConditionalDisplayService.conditionalDisplayToBeShown(chosen, cd)) {
                return true;
            }
        }
        return false;
    }

    private static List<ItemMeta> items(Connection c, int crfVersionId) throws SQLException {
        List<ItemMeta> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(ITEMS_SQL)) {
            ps.setInt(1, crfVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int groupId = rs.getInt(8);
                    Integer group = rs.wasNull() ? null : groupId;
                    out.add(new ItemMeta(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getBoolean(6), rs.getBoolean(7), group,
                            group != null && rs.getBoolean(9), rs.getBoolean(10)));
                }
            }
        }
        return out;
    }

    /**
     * item id → ordinal → value.
     *
     * @param noted receives {@code itemId:ordinal} of every row with an active note
     */
    private static Map<Integer, Map<Integer, String>> values(Connection c, int eventCrfId, Set<String> noted)
            throws SQLException {
        Map<Integer, Map<Integer, String>> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(VALUES_SQL)) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int ordinal = Math.max(1, rs.getInt(2));
                    out.computeIfAbsent(rs.getInt(1), k -> new LinkedHashMap<>())
                            .put(ordinal, rs.getString(3));
                    if (rs.getBoolean(4)) {
                        noted.add(rs.getInt(1) + ":" + ordinal);
                    }
                }
            }
        }
        return out;
    }

    private static Set<Integer> ids(Connection c, String sql, int eventCrfId) throws SQLException {
        Set<Integer> out = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        }
        return out;
    }

    /**
     * item_form_metadata id → its conditions as (control item id, option).
     * The control item is named by its metadata row or, in older CRFs, only
     * by its name, which the SPA's authoring writes as the OID.
     */
    private static Map<Integer, List<String[]>> scd(Connection c, int crfVersionId, List<ItemMeta> items)
            throws SQLException {
        Map<Integer, Integer> itemIdByIfm = new HashMap<>();
        Map<String, Integer> itemIdByName = new HashMap<>();
        for (ItemMeta item : items) {
            itemIdByIfm.put(item.ifmId(), item.itemId());
            if (item.name() != null) itemIdByName.put(item.name(), item.itemId());
            if (item.oid() != null) itemIdByName.putIfAbsent(item.oid(), item.itemId());
        }
        Map<Integer, List<String[]>> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(SCD_SQL)) {
            ps.setInt(1, crfVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer controlItemId = itemIdByIfm.get(rs.getInt(2));
                    if (controlItemId == null && rs.getString(3) != null) {
                        controlItemId = itemIdByName.get(rs.getString(3).trim());
                    }
                    // A control item that is not on the form: the condition can never hold.
                    String control = controlItemId == null ? "-1" : String.valueOf(controlItemId);
                    out.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>())
                            .add(new String[] {control, rs.getString(4)});
                }
            }
        }
        return out;
    }
}
