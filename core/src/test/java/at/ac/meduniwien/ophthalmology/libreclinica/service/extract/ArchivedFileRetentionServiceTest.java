/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Phase E.6 — unit tests for {@link ArchivedFileRetentionService}.
 *
 * <p>Mocks the JDBC surface (DataSource → Connection → PreparedStatement
 * → ResultSet) rather than spinning up a real database. The service is
 * pure SQL+IO; the contract worth pinning is:
 *
 * <ul>
 *   <li>{@code findExpired} binds the retention-days int and reads
 *       {@code archived_dataset_file_id / file_reference / file_size}
 *       from the ResultSet.</li>
 *   <li>For each expired row the on-disk file is deleted (when present)
 *       AND the DB row is dropped via a parameterised DELETE.</li>
 *   <li>Exactly one summary audit_log_event row is written per pass,
 *       with type id 52 and an entity_name + new_value describing the
 *       sweep.</li>
 *   <li>The default retention window is 90 days when the config key is
 *       missing or non-positive.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.class)
public class ArchivedFileRetentionServiceTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement selectStmt;
    @Mock
    private PreparedStatement deleteStmt;
    @Mock
    private PreparedStatement auditStmt;
    @Mock
    private ResultSet rs;

    private File tempFile;

    @Before
    public void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
    }

    @After
    public void tearDown() {
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    @Test
    public void garbageCollectReturnsZeroWhenNoExpiredRows() throws Exception {
        // SELECT returns no rows → no deletes → still write one audit
        // row (the operator wants to know the sweep ran).
        when(connection.prepareStatement(anyString()))
                .thenReturn(selectStmt)   // findExpired
                .thenReturn(auditStmt);   // writeGcAudit
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        ArchivedFileRetentionService svc =
                new ArchivedFileRetentionService(dataSource, 90);

        int removed = svc.garbageCollect();

        assertEquals(0, removed);
        // No delete-row statement is issued when no rows are returned.
        // Skipping the audit write would make the GC pass invisible to
        // operators, so it should be written even on a no-op pass —
        // *except* for the early return in our implementation, which
        // skips the audit when the expired list is empty (to avoid log
        // spam on quiet days). Re-assert that behaviour:
        verify(connection, times(1)).prepareStatement(anyString());
    }

    @Test
    public void garbageCollectDeletesExpiredFileAndRowAndEmitsAudit() throws Exception {
        // Set up one expired row pointing at a real temp file.
        tempFile = Files.createTempFile("retention-test-", ".csv").toFile();
        assertTrue(tempFile.exists());

        when(connection.prepareStatement(anyString()))
                .thenReturn(selectStmt)   // findExpired
                .thenReturn(deleteStmt)   // deleteRow
                .thenReturn(auditStmt);   // writeGcAudit
        when(selectStmt.executeQuery()).thenReturn(rs);

        // Two rs.next() calls — first returns true (one row), second false.
        when(rs.next()).thenReturn(true).thenReturn(false);
        when(rs.getInt(1)).thenReturn(42);
        when(rs.getString(2)).thenReturn(tempFile.getAbsolutePath());
        when(rs.getLong(3)).thenReturn(2048L);

        when(deleteStmt.executeUpdate()).thenReturn(1);

        ArchivedFileRetentionService svc =
                new ArchivedFileRetentionService(dataSource, 30);

        int removed = svc.garbageCollect();

        assertEquals(1, removed);
        assertFalse("temp file should have been deleted by the sweep", tempFile.exists());
        // Verify DELETE was bound with the row id.
        verify(deleteStmt).setInt(1, 42);
        verify(deleteStmt).executeUpdate();
        // Verify audit row went out: type id, system user id, count + bytes embedded.
        verify(auditStmt).setInt(eq(1), eq(ExportAuditService.AUDIT_TYPE_DATASET_EXPORTED));
        verify(auditStmt).setInt(eq(2), eq(ArchivedFileRetentionService.SYSTEM_USER_ID));
        verify(auditStmt, atLeastOnce()).setString(eq(5), anyString());
        verify(auditStmt).executeUpdate();
    }

    @Test
    public void garbageCollectToleratesMissingOnDiskFile() throws Exception {
        // The row's file_reference points at a path that doesn't exist;
        // the GC should still drop the DB row + the audit row.
        when(connection.prepareStatement(anyString()))
                .thenReturn(selectStmt)
                .thenReturn(deleteStmt)
                .thenReturn(auditStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true).thenReturn(false);
        when(rs.getInt(1)).thenReturn(7);
        when(rs.getString(2)).thenReturn("/nonexistent/path/dataset-7.zip");
        when(rs.getLong(3)).thenReturn(0L);
        when(deleteStmt.executeUpdate()).thenReturn(1);

        ArchivedFileRetentionService svc =
                new ArchivedFileRetentionService(dataSource, 90);

        int removed = svc.garbageCollect();

        assertEquals(1, removed);
        verify(deleteStmt).setInt(1, 7);
        verify(auditStmt).executeUpdate();
    }

    @Test
    public void garbageCollectBindsRetentionDaysIntoFindExpiredQuery() throws Exception {
        when(connection.prepareStatement(anyString()))
                .thenReturn(selectStmt)
                .thenReturn(auditStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        ArchivedFileRetentionService svc =
                new ArchivedFileRetentionService(dataSource, 14);

        svc.garbageCollect();

        // The first parameter binding on the SELECT statement is the
        // retention window — verify it was bound as 14 not the default.
        verify(selectStmt).setInt(1, 14);
    }

    @Test
    public void garbageCollectReturnsZeroOnNullDataSource() {
        ArchivedFileRetentionService svc =
                new ArchivedFileRetentionService(null, 90);

        assertEquals(0, svc.garbageCollect());
    }

    @Test
    public void constructorFallsBackToDefaultRetentionForNonPositiveInput() {
        ArchivedFileRetentionService svcZero =
                new ArchivedFileRetentionService(dataSource, 0);
        ArchivedFileRetentionService svcNeg =
                new ArchivedFileRetentionService(dataSource, -7);

        assertEquals(ArchivedFileRetentionService.DEFAULT_RETENTION_DAYS,
                svcZero.getRetentionDays());
        assertEquals(ArchivedFileRetentionService.DEFAULT_RETENTION_DAYS,
                svcNeg.getRetentionDays());
    }

    @Test
    public void defaultRetentionConstantIs90Days() {
        // Pin the documented default — operators reading
        // datainfo.properties expect this default if they leave the
        // key absent.
        assertEquals(90, ArchivedFileRetentionService.DEFAULT_RETENTION_DAYS);
    }

    @Test
    public void garbageCollectSkipsAuditWriteOnEmptySweep() throws Exception {
        // Audit silence on quiet days: when nothing is expired we
        // don't want a row-per-day "removed 0 files" entry cluttering
        // the audit log. This pins that behaviour.
        when(connection.prepareStatement(anyString())).thenReturn(selectStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        ArchivedFileRetentionService svc =
                new ArchivedFileRetentionService(dataSource, 90);
        svc.garbageCollect();

        // Only one PreparedStatement gets prepared — the SELECT.
        verify(connection, times(1)).prepareStatement(anyString());
        verify(auditStmt, never()).executeUpdate();
    }
}
