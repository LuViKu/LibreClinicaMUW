/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The CRF view's item table and its integrity check, the port of the legacy
 * {@code ViewCRFServlet.verifyUniqueItemPlacementInGroups}: an item keeps one
 * item group across the versions of its CRF.
 */
class CrfItemTableTest {

    private static final int AVAILABLE = 1;
    private static final int REMOVED = 5;

    private static CrfItemTable.Row row(String item, String version, int versionStatus, String group) {
        return new CrfItemTable.Row(item, "I_" + item, "desc " + item, 5, version, versionStatus, group);
    }

    @Test
    void anItemInTheSameGroupInEveryVersionIsOkAndListsItsVersions() {
        List<CrfDetailDto.Item> items = CrfItemTable.build(List.of(
                row("AGE", "v1", AVAILABLE, "G1"),
                row("AGE", "v2", AVAILABLE, "G1")));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).integrity()).isEqualTo(CrfItemTable.OK);
        assertThat(items.get(0).versions()).containsExactly("v1", "v2");
        assertThat(items.get(0).placements()).isEmpty();
        assertThat(items.get(0).dataType()).isEqualTo("ST");
    }

    @Test
    void anItemThatMovedGroupsInAnAvailableVersionIsAProblemWithBothPlacements() {
        CrfDetailDto.Item item = CrfItemTable.build(List.of(
                row("AGE", "v1", AVAILABLE, "G1"),
                row("AGE", "v2", AVAILABLE, "G2"))).get(0);

        assertThat(item.integrity()).isEqualTo(CrfItemTable.PROBLEM);
        assertThat(item.placements()).containsExactly(
                new CrfDetailDto.Placement("G1", "v1"),
                new CrfDetailDto.Placement("G2", "v2"));
    }

    @Test
    void aConflictOnlyAmongVersionsThatTakeNoDataIsAWarning() {
        CrfDetailDto.Item item = CrfItemTable.build(List.of(
                row("AGE", "v1", REMOVED, "G1"),
                row("AGE", "v2", REMOVED, "G2"),
                row("AGE", "v3", REMOVED, "G1"))).get(0);

        assertThat(item.integrity()).isEqualTo(CrfItemTable.WARNING);
        assertThat(item.placements()).containsExactly(
                new CrfDetailDto.Placement("G1", "v1"),
                new CrfDetailDto.Placement("G2", "v2"));
    }

    @Test
    void itemsWithoutAGroupAreConsistentAndKeepTheirOrder() {
        List<CrfDetailDto.Item> items = CrfItemTable.build(List.of(
                row("A", "v1", AVAILABLE, null),
                row("A", "v2", AVAILABLE, null),
                row("B", "v2", AVAILABLE, null)));

        assertThat(items).extracting(CrfDetailDto.Item::name).containsExactly("A", "B");
        assertThat(items).allMatch(i -> CrfItemTable.OK.equals(i.integrity()));
    }

    @Test
    void theDataTypeIsTheShortCode() {
        assertThat(CrfItemTable.dataTypeCode(6)).isEqualTo("INT");
        assertThat(CrfItemTable.dataTypeCode(10)).isEqualTo("PDATE");
        assertThat(CrfItemTable.dataTypeCode(0)).isEmpty();
        assertThat(CrfItemTable.dataTypeCode(999)).isEmpty();
    }
}
