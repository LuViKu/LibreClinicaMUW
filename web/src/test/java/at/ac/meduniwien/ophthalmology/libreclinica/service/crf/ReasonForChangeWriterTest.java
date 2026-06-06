/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.crf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DiscrepancyNoteType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.DiscrepancyNoteDAO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Phase E.6 admin-rfc — ReasonForChangeWriter unit-test coverage.
 *
 * <p>Pins the threading rule (first RFC writes a parent;
 * subsequent RFCs thread as children) and the failure-tolerance
 * contract (DAO failure does not throw — caller keeps going so the
 * item_data save commits even when the RFC sidecar fails).
 */
class ReasonForChangeWriterTest {

    private DiscrepancyNoteDAO dao;
    private ReasonForChangeWriter writer;
    private StudyBean study;
    private UserAccountBean actor;

    @BeforeEach
    void setUp() {
        dao = Mockito.mock(DiscrepancyNoteDAO.class);
        writer = new ReasonForChangeWriter(dao);

        study = new StudyBean();
        study.setId(7);
        study.setOid("S_FAKE7");
        study.setName("Fake Study");

        actor = new UserAccountBean();
        actor.setId(42);
        actor.setName("dr.demo");

        // Stub create() to return the bean with a non-zero id (simulating
        // executeUpdateWithPK populating the PK).
        when(dao.create(any(DiscrepancyNoteBean.class))).thenAnswer(inv -> {
            DiscrepancyNoteBean sb = inv.getArgument(0);
            sb.setId(1001);
            return sb;
        });
    }

    @Test
    void writesParentWhenNoPriorRfcExists() {
        when(dao.findLatestRfcParentForItemData(500)).thenReturn(0);

        DiscrepancyNoteBean dn = writer.writeRfc(500, study, actor, "Correcting transcription");

        assertNotNull(dn, "DAO returned a created note");
        assertEquals(DiscrepancyNoteType.REASON_FOR_CHANGE.getId(),
                dn.getDiscrepancyNoteTypeId());
        assertEquals(0, dn.getParentDnId(), "first RFC becomes the parent itself");
        assertEquals(500, dn.getEntityId());
        assertEquals("itemData", dn.getEntityType());
        assertEquals("value", dn.getColumn());
        assertEquals(7, dn.getStudyId());
        assertEquals(42, dn.getOwnerId());
        assertEquals("Correcting transcription", dn.getDetailedNotes());
        assertTrue(dn.isActivated());

        verify(dao, times(1)).create(any(DiscrepancyNoteBean.class));
        verify(dao, times(1)).createMapping(any(DiscrepancyNoteBean.class));
    }

    @Test
    void threadsAsChildWhenPriorRfcParentExists() {
        when(dao.findLatestRfcParentForItemData(500)).thenReturn(900);

        ArgumentCaptor<DiscrepancyNoteBean> captor =
                ArgumentCaptor.forClass(DiscrepancyNoteBean.class);

        DiscrepancyNoteBean dn = writer.writeRfc(500, study, actor, "Follow-up correction");

        assertNotNull(dn);
        verify(dao, times(1)).create(captor.capture());
        assertEquals(900, captor.getValue().getParentDnId(),
                "subsequent RFC threads under the existing parent");
    }

    @Test
    void daoFailureDoesNotThrowAndReturnsNull() {
        when(dao.findLatestRfcParentForItemData(anyInt())).thenReturn(0);
        when(dao.create(any(DiscrepancyNoteBean.class)))
                .thenThrow(new RuntimeException("simulated DAO failure"));

        DiscrepancyNoteBean dn = writer.writeRfc(500, study, actor, "Reason");

        assertNull(dn, "writer swallows DAO failure and returns null");
        verify(dao, never()).createMapping(any(DiscrepancyNoteBean.class));
    }

    @Test
    void skipsWriteOnBlankReasonText() {
        DiscrepancyNoteBean dn = writer.writeRfc(500, study, actor, "   ");

        assertNull(dn, "blank reason short-circuits before DAO touch");
        verify(dao, never()).create(any(DiscrepancyNoteBean.class));
    }

    @Test
    void skipsWriteOnNullItemDataId() {
        DiscrepancyNoteBean dn = writer.writeRfc(0, study, actor, "Reason");

        assertNull(dn, "non-positive itemDataId short-circuits");
        verify(dao, never()).create(any(DiscrepancyNoteBean.class));
    }
}
