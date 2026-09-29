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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.odmbeans.AuditLogBean;

/**
 * The subject audit trail in the clinical-data export is in time order
 * (2026-09-29).
 *
 * <p>{@code AuditLogBean.compareTo(AuditLogBean)} overloaded rather than
 * overrode {@code Comparable<ElementOIDBean>}, so {@code Collections.sort}
 * ordered the merged study-subject, group and subject entries by their OID
 * string: AL_1000 before AL_200, whatever their times.
 */
public class SubjectAuditLogOrderTest {

    private static AuditLogBean entry(int auditId, long epochMillis) {
        AuditLogBean b = new AuditLogBean();
        b.setOid("AL_" + auditId);
        b.setDatetimeStamp(new Date(epochMillis));
        return b;
    }

    private static List<String> oids(List<AuditLogBean> logs) {
        List<String> out = new ArrayList<>();
        for (AuditLogBean b : logs) {
            out.add(b.getOid());
        }
        return out;
    }

    @Test
    public void entriesAreOrderedByTimeNotByTheirOidString() {
        List<AuditLogBean> logs = new ArrayList<>();
        logs.add(entry(200, 3_000L));
        logs.add(entry(1000, 1_000L));
        logs.add(entry(5, 4_000L));
        logs.add(entry(30, 2_000L));

        GenerateClinicalDataServiceImpl.sortAuditLogsChronologically(logs);

        assertEquals(List.of("AL_1000", "AL_30", "AL_200", "AL_5"), oids(logs));
    }

    @Test
    public void entriesOfTheSameInstantFollowTheAuditSequence() {
        // One transaction writes several audit rows with the same timestamp.
        List<AuditLogBean> logs = new ArrayList<>();
        logs.add(entry(1000, 1_000L));
        logs.add(entry(999, 1_000L));
        logs.add(entry(1001, 1_000L));

        GenerateClinicalDataServiceImpl.sortAuditLogsChronologically(logs);

        assertEquals(List.of("AL_999", "AL_1000", "AL_1001"), oids(logs));
    }

    @Test
    public void anEntryWithoutATimeGoesLast() {
        List<AuditLogBean> logs = new ArrayList<>();
        AuditLogBean undated = entry(1, 0L);
        undated.setDatetimeStamp(null);
        logs.add(undated);
        logs.add(entry(2, 5_000L));

        GenerateClinicalDataServiceImpl.sortAuditLogsChronologically(logs);

        assertEquals(List.of("AL_2", "AL_1"), oids(logs));
    }
}
