/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ItemDataType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;

/**
 * The item table of the CRF view: one row per item, with the versions it is
 * in and the legacy integrity check. Pure, so the check is testable without
 * a database.
 *
 * <p>Legacy parity: {@code ViewCRFServlet.verifyUniqueItemPlacementInGroups}.
 * An item must sit in the same item group in every version of its CRF. The
 * first placement read is the reference; every placement in another group
 * is a conflict, reported with the reference. The check is a
 * {@code problem} when the reference or a conflicting placement is in an
 * available version, and a {@code warning} when all of them are in versions
 * that no longer take data.
 */
final class CrfItemTable {

    /** One (item, version) placement as read from the database, ordered by item name. */
    record Row(String itemName, String itemOid, String description, int dataTypeId,
               String versionName, int versionStatusId, String groupLabel) {}

    static final String OK = "ok";
    static final String WARNING = "warning";
    static final String PROBLEM = "problem";

    private CrfItemTable() {
    }

    static List<CrfDetailDto.Item> build(List<Row> rows) {
        Map<String, List<Row>> byItem = new LinkedHashMap<>();
        for (Row row : rows) {
            byItem.computeIfAbsent(row.itemName(), _ -> new ArrayList<>()).add(row);
        }
        List<CrfDetailDto.Item> out = new ArrayList<>(byItem.size());
        for (List<Row> placements : byItem.values()) {
            Row reference = placements.get(0);
            List<String> versions = new ArrayList<>();
            List<CrfDetailDto.Placement> conflicts = new ArrayList<>();
            boolean inAvailableVersion = reference.versionStatusId() == Status.AVAILABLE.getId();
            for (Row row : placements) {
                if (!versions.contains(row.versionName())) versions.add(row.versionName());
                if (row == reference) continue;
                if (!Objects.equals(label(row), label(reference))) {
                    if (conflicts.isEmpty()) {
                        conflicts.add(new CrfDetailDto.Placement(label(reference), reference.versionName()));
                    }
                    conflicts.add(new CrfDetailDto.Placement(label(row), row.versionName()));
                    if (row.versionStatusId() == Status.AVAILABLE.getId()) inAvailableVersion = true;
                }
            }
            String integrity = conflicts.isEmpty() ? OK : (inAvailableVersion ? PROBLEM : WARNING);
            out.add(new CrfDetailDto.Item(
                    reference.itemName(),
                    reference.itemOid(),
                    reference.description() == null ? "" : reference.description(),
                    dataTypeCode(reference.dataTypeId()),
                    versions,
                    integrity,
                    conflicts));
        }
        return out;
    }

    /**
     * The short data type code the authoring canvas uses (ST, INT, REAL, DATE,
     * PDATE, FILE, BL, ...): {@link ItemDataType}'s code, upper-cased, as the
     * version-contents endpoint reports it. Empty for an unknown id.
     */
    static String dataTypeCode(int dataTypeId) {
        String code = ItemDataType.get(dataTypeId).getName();
        return code == null || code.isEmpty() || "invalid".equals(code) ? "" : code.toUpperCase();
    }

    private static String label(Row row) {
        return row.groupLabel() == null ? "" : row.groupLabel();
    }
}
