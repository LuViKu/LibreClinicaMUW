/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DatasetItemStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.SynchronousExportMaterializer;

/**
 * Characterisation IT for dataset extraction.
 *
 * <p>Written as the safety net for the export work: a real dataset over the
 * seeded demo study is materialised in each format and the produced archive is
 * opened and inspected. This is deliberately structural rather than a
 * byte-for-byte golden — extracts embed timestamps, run directories and
 * generated file ids, so a literal golden would need re-capturing constantly
 * and would assert nothing about correctness.
 *
 * <p>The ODM case is the one that matters most: until 2026-09-18
 * {@code GenerateExtractFileService} dropped the {@code ruleSetRuleDao} and
 * {@code coreResources} its constructor was handed, so every ODM export that
 * did not run through the Quartz XSLT job died with a NullPointerException
 * inside the metadata collector. MIGRATION.md has listed the ODM
 * characterisation ITs as pending since Phase 0; this closes that gap.
 */
@SuppressWarnings("null")
class DatasetExportCharacterisationDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Stands in for the configured {@code filePath}; run dirs are created beneath it. */
    @TempDir
    static Path FILE_ROOT;

    private static java.util.Properties SAVED_DATAINFO;

    /** Demographics CRF v1 — owns the demo items 1..5 this fixture extracts. */
    private static final int DEMO_CRF_ID = 1;
    private static final int DEMO_CRF_VERSION_ID = 1;

    @BeforeAll
    static void overrideFilePath() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        assertNotNull(live, "DATAINFO must be set by AbstractApiControllerDatabaseIT");
        SAVED_DATAINFO = new java.util.Properties();
        SAVED_DATAINFO.putAll(live);
        live.setProperty("filePath", FILE_ROOT.toString() + File.separator);

        ensureUngroupedItemGroup();
    }

    /**
     * The seeded demo CRFs are authored by Liquibase and — unlike CRFs uploaded
     * through the application, which always get an "Ungrouped" item group — have
     * no {@code item_group_metadata} rows at all. The extract assembles three
     * parallel result sets (item-data ids, event side, item-group side) and
     * indexes them in lockstep, so an ungrouped item yields rows on two of the
     * three and {@code ExtractBean.addStudyEventData} dies with
     * IndexOutOfBoundsException.
     *
     * <p>This fixture therefore creates the same "Ungrouped" group the upload
     * path would have created, so the test exercises extraction rather than that
     * seed gap. NOTE: the gap itself is real and affects the nAMD visit CRF
     * (also Liquibase-seeded, also ungrouped) — tracked as a Phase 1 item; a
     * dataset containing those items cannot be exported today.
     */
    private static void ensureUngroupedItemGroup() throws Exception {
        try (var c = DATA_SOURCE.getConnection()) {
            Integer groupId;
            try (var ps = c.prepareStatement(
                    "SELECT item_group_id FROM item_group WHERE oc_oid = 'IG_DEMOG_UNGROUPED'");
                 var rs = ps.executeQuery()) {
                groupId = rs.next() ? rs.getInt(1) : null;
            }
            if (groupId == null) {
                try (var ps = c.prepareStatement(
                        "INSERT INTO item_group (name, crf_id, status_id, date_created, owner_id, oc_oid) "
                                + "VALUES ('Ungrouped', ?, 1, NOW(), 1, 'IG_DEMOG_UNGROUPED') "
                                + "RETURNING item_group_id")) {
                    ps.setInt(1, DEMO_CRF_ID);
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        groupId = rs.getInt(1);
                    }
                }
            }
            try (var ps = c.prepareStatement(
                    "INSERT INTO item_group_metadata "
                            + "  (item_group_id, crf_version_id, item_id, ordinal, "
                            + "   show_group, repeating_group) "
                            + "SELECT ?, ?, i.item_id, i.item_id, true, false FROM item i "
                            + " WHERE i.item_id BETWEEN 1 AND 5 "
                            + "   AND NOT EXISTS (SELECT 1 FROM item_group_metadata m "
                            + "                    WHERE m.item_id = i.item_id "
                            + "                      AND m.crf_version_id = ?)")) {
                ps.setInt(1, groupId);
                ps.setInt(2, DEMO_CRF_VERSION_ID);
                ps.setInt(3, DEMO_CRF_VERSION_ID);
                ps.executeUpdate();
            }
            try (var ps = c.prepareStatement(
                    "SELECT count(*) FROM item_group_metadata WHERE crf_version_id = ? "
                            + "AND item_id BETWEEN 1 AND 5")) {
                ps.setInt(1, DEMO_CRF_VERSION_ID);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertTrue(rs.getInt(1) >= 5,
                            "fixture did not create the item-group rows the extract needs");
                }
            }
        }
    }

    /** True when the item's form metadata carries a width_decimal value. */
    private static boolean hasWidthDecimal(int itemId) throws Exception {
        try (var c = DATA_SOURCE.getConnection();
             var ps = c.prepareStatement(
                     "SELECT count(*) FROM item_form_metadata WHERE item_id = ? "
                             + "AND width_decimal IS NOT NULL AND width_decimal <> ''")) {
            ps.setInt(1, itemId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    @AfterAll
    static void restoreDatainfo() throws Exception {
        java.lang.reflect.Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        java.util.Properties live = (java.util.Properties) f.get(null);
        if (live != null && SAVED_DATAINFO != null) {
            live.clear();
            live.putAll(SAVED_DATAINFO);
        }
    }

    /**
     * A dataset over the seeded study: the three demo visit definitions and the
     * five items that actually carry demo item_data (consent date/signed,
     * height, weight, systolic BP).
     */
    private DatasetBean persistDataset(String name) {
        return persistDataset(name, java.util.List.of(1, 2, 3, 4, 5));
    }

    private DatasetBean persistDataset(String name, java.util.List<Integer> itemIds) {
        UserAccountBean root = (UserAccountBean) new UserAccountDAO(DATA_SOURCE).findByPK(1);
        DatasetBean ds = new DatasetBean();
        ds.setStudyId(1);
        ds.setName(name);
        ds.setDescription("characterisation fixture");
        ds.setStatus(Status.AVAILABLE);
        ds.setDatasetItemStatus(DatasetItemStatus.COMPLETED_AND_NONCOMPLETED);
        ds.setOwner(root);
        ds.setOwnerId(1);
        ds.setCreatedDate(new Date());
        ds.setNumRuns(0);
        // Non-null TEXT columns in the schema — the legacy create path stores "".
        ds.setODMMetaDataVersionName("");
        ds.setODMMetaDataVersionOid("");
        ds.setODMPriorStudyOid("");
        ds.setODMPriorMetaDataVersionOid("");
        ds.setEventIds(new ArrayList<>(java.util.List.of(1, 2, 3)));
        ds.setItemIds(new ArrayList<>(itemIds));
        ds.setShowSubjectUniqueIdentifier(true);
        ds.setShowEventStart(true);
        ds.setShowSubjectStatus(true);
        ds.setSQLStatement(ds.generateQuery());

        DatasetBean persisted = new DatasetDAO(DATA_SOURCE).create(ds);
        assertNotNull(persisted);
        assertTrue(persisted.getId() > 0, "the fixture dataset should persist");
        return persisted;
    }

    private SynchronousExportMaterializer materializer() {
        RuleSetRuleDao rules = Mockito.mock(RuleSetRuleDao.class);
        Mockito.when(rules.findByRuleSetStudyIdAndStatusAvail(Mockito.anyInt()))
                .thenReturn(new ArrayList<>());
        return new SynchronousExportMaterializer(
                DATA_SOURCE, Mockito.mock(CoreResources.class), rules);
    }

    /** Reads the produced archive back as text (extract output is zipped). */
    private static String readArchive(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            // Show what the run actually produced — the recorded file_reference
            // and the file on disk have disagreed before.
            StringBuilder seen = new StringBuilder();
            Path dir = path.getParent();
            if (dir != null && Files.isDirectory(dir)) {
                try (var s = Files.list(dir)) {
                    s.forEach(p -> seen.append("\n  ").append(p.getFileName()));
                }
            } else {
                seen.append("\n  (run directory ").append(dir).append(" does not exist)");
            }
            throw new AssertionError("no archive at " + path + "\nrun directory contains:" + seen);
        }
        if (!path.getFileName().toString().endsWith(".zip")) {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        StringBuilder all = new StringBuilder();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                all.append("=== ").append(e.getName()).append(" ===\n");
                all.append(new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }

    /** Resolves the on-disk archive for an archived_dataset_file id. */
    private static Path archivePathFor(int archivedFileId) throws Exception {
        try (var c = DATA_SOURCE.getConnection();
             var ps = c.prepareStatement(
                     "SELECT file_reference FROM archived_dataset_file WHERE archived_dataset_file_id = ?")) {
            ps.setInt(1, archivedFileId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no archived_dataset_file row " + archivedFileId);
                return Path.of(rs.getString("file_reference"));
            }
        }
    }

    private static int idFromResult(at.ac.meduniwien.ophthalmology.libreclinica.service.extract.ExportFileMaterializer.Result r) {
        // The materializer signals "row already persisted" with an "existing:<id>" sentinel.
        String ref = r.fileReference();
        assertTrue(ref.startsWith("existing:"), "unexpected file reference: " + ref);
        return Integer.parseInt(ref.substring("existing:".length()));
    }

    /**
     * DEFECT PIN — ODM export of a Liquibase-seeded CRF.
     *
     * <p>Verified 2026-09-18 by running this export with and without the
     * dependency-wiring change in {@code GenerateExtractFileService}: the
     * failure is identical either way, so the latent NPE that wiring removes is
     * NOT what blocks ODM here. The real blocker is a format mismatch —
     * {@code item_form_metadata.width_decimal} is stored as {@code "(4,1)"} by
     * the seed migrations, while {@link
     * at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.OdmExtractDAO}'s
     * {@code parseDecimal} expects OpenClinica's {@code "4(1)"} form and so
     * calls {@code Integer.parseInt("4,1")}.
     *
     * <p>Scope: Demographics (demo), Ophthalmology Visit, and — the reason this
     * matters for the AI pilot — the nAMD Treat-and-Extend Visit CRF, 12 items.
     * HealthAEye's CRF was authored in the application and stores no
     * width_decimal at all, so it is unaffected.
     *
     * <p>Delete this test and enable {@link #odmExport_succeedsForItemsWithoutWidthDecimal}
     * for the full item set once the seeded width_decimal values are corrected.
     */
    @Test
    void odmExport_succeedsForACrfWhoseWidthDecimalUsesTheSqlSpelling() throws Exception {
        assertTrue(hasWidthDecimal(4), "fixture assumption: item 4 carries a width_decimal");
        DatasetBean ds = persistDataset("IT_ODM_SQLWD_" + System.nanoTime(),
                java.util.List.of(1, 2, 3, 4, 5));

        String text = readArchive(archivePathFor(idFromResult(materializer().materialize(ds, "odm", 1))));
        assertTrue(text.contains("ODM"), "expected an ODM document, got " + text.length() + " chars");
        assertTrue(text.contains("M-001"), "the seeded subject should appear in the ODM export");
    }

    /**
     * The defect this guards was not avoidable by deselecting the offending
     * item — ODM metadata is collected per CRF version, not per selected item —
     * so this exercises the other half of the blast radius: a dataset that does
     * not name the bad item still pulls its CRF's metadata.
     */
    @Test
    void odmExport_succeedsWhenTheOffendingItemIsNotSelected() throws Exception {
        DatasetBean ds = persistDataset("IT_ODM_" + System.nanoTime(),
                java.util.List.of(1, 2, 3, 5));

        String text = readArchive(archivePathFor(idFromResult(materializer().materialize(ds, "odm", 1))));
        assertTrue(text.contains("ODM"), "expected an ODM document, got " + text.length() + " chars");
        assertTrue(text.contains("M-001"), "the seeded subject should appear in the ODM export");
    }

    /**
     * DIAGNOSTIC — why the tab/CSV extract dies.
     *
     * <p>{@code ExtractBean.addStudyEventData} walks three collections in
     * lockstep; the item-group one comes back empty while the item-data one has
     * rows, so it throws IndexOutOfBounds. Its loader
     * ({@code EntityDAO.loadBASE_ITEMGROUPSIDEHashMap}) swallows SQLException
     * and returns false, which would look exactly the same from the outside.
     * This test distinguishes the two: it runs the loader directly and reports
     * whether the query failed or simply matched nothing.
     */
    @Test
    void diagnostic_itemGroupSideLoader_reportsWhyItIsEmpty() throws Exception {
        DatasetBean ds = persistDataset("IT_DIAG_" + System.nanoTime(),
                java.util.List.of(1, 2, 3, 5));
        var studyDao = new at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO(DATA_SOURCE);
        var study = (at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean)
                studyDao.findByPK(1);

        var extractService = new at.ac.meduniwien.ophthalmology.libreclinica.service.extract
                .GenerateExtractFileService(DATA_SOURCE, Mockito.mock(CoreResources.class),
                        Mockito.mock(RuleSetRuleDao.class));
        var eb = extractService.generateExtractBean(ds, study,
                new at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean());

        var dao = new at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO(DATA_SOURCE);
        // Derive the IN-lists exactly the way getDatasetData does, so the
        // diagnostic exercises the real arguments rather than hand-built ones.
        String sql = ds.getSQLStatement();
        String sedIn = dao.parseSQLDataset(sql, true, true);
        String itemIn = dao.parseSQLDataset(sql, false, true);
        System.out.println("[diagnostic] sedIn=<" + sedIn + "> itemIn=<" + itemIn + ">");

        boolean eventSideOk = dao.loadBASE_EVENTINSIDEHashMap(1, 1, sedIn, itemIn, eb);
        System.out.println("[diagnostic] event-side loader ok=" + eventSideOk
                + (eventSideOk ? "" : " failure=" + dao.getFailureDetails()));

        boolean ok = dao.loadBASE_ITEMGROUPSIDEHashMap(1, 1, sedIn, itemIn, eb);
        if (!ok) {
            throw new AssertionError("the item-group query FAILED (swallowed SQLException): "
                    + dao.getFailureDetails());
        }
        System.out.println("[diagnostic] item-group loader succeeded; "
                + "an empty result set would be a data-shape problem, not a SQL error");
    }

    /**
     * DEFECT PIN — tab/Excel export over the seeded study.
     *
     * <p>{@code ExtractBean.addStudyEventData} indexes three collections in
     * lockstep and the item-group one is empty while the item-data one has rows,
     * so it throws IndexOutOfBounds instead of producing a file.
     *
     * <p>What is established: the failure is reproducible, and it is NOT a
     * swallowed SQL error — {@link #diagnostic_itemGroupSideLoader_reportsWhyItIsEmpty}
     * shows both loaders execute successfully against the same arguments the
     * export derives. Adding the "Ungrouped" item-group rows the application's
     * own CRF-upload path creates (see {@link #ensureUngroupedItemGroup()}) does
     * not resolve it either, so the remaining cause is a data-shape mismatch
     * that still needs pinning before the extract can be trusted.
     */
    @Test
    void tabExport_forSeededStudy_currentlyThrows() {
        DatasetBean ds = persistDataset("IT_TAB_" + System.nanoTime(),
                java.util.List.of(1, 2, 3, 5));
        org.junit.jupiter.api.Assertions.assertThrows(IndexOutOfBoundsException.class,
                () -> materializer().materialize(ds, "tab", 1));
    }

    /** CSV goes through a different report bean but dies in the same place. */
    @Test
    void csvExport_forSeededStudy_currentlyThrows() {
        DatasetBean ds = persistDataset("IT_CSV_" + System.nanoTime(),
                java.util.List.of(1, 2, 3, 5));
        org.junit.jupiter.api.Assertions.assertThrows(IndexOutOfBoundsException.class,
                () -> materializer().materialize(ds, "csv", 1));
    }

    /**
     * Pins the known SAS defect so the P1-7 work has a failing-to-passing
     * signal: today the branch writes an empty file, which this asserts
     * explicitly rather than pretending it works.
     */
    @Test
    void sasExport_currentlyProducesAnEmptyPayload() throws Exception {
        DatasetBean ds = persistDataset("IT_SAS_" + System.nanoTime());
        var result = materializer().materialize(ds, "sas", 1);

        String text = readArchive(archivePathFor(idFromResult(result)));
        // Only the zip entry header, no SAS syntax — see plan item P1-7.
        assertTrue(!text.contains("PROC ") && !text.contains("INFILE"),
                "SAS export unexpectedly has content — update P1-7 and this characterisation");
    }
}
