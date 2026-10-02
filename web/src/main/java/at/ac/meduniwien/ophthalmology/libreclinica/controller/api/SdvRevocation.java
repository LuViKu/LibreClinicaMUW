/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;

/**
 * Withdraws source data verification when the data under it change.
 *
 * <p>SDV attests that an event CRF's data match the source, so a change to
 * the data ends the attestation. Legacy administrative editing
 * ({@code DataEntryServlet}) does this whenever a save changes an item of a
 * verified event CRF: it sets {@code sdv_status} false and records the
 * editor in {@code sdv_update_id}. The {@code event_crf} trigger then writes
 * the audit row, type 32 ("EventCRF SDV Status", TRUE to FALSE), attributed
 * to that editor. The SPA's write paths call {@link #revokeIfVerified} after
 * they change item data, so the flag is cleared the same way, with the same
 * audit row, and the SDV page offers the CRF again.
 *
 * <p>On the same path legacy also sets the subject's status back to
 * available. That is left out: it undid a subject's signature, which the SPA
 * handles by refusing writes to signed CRFs, and here it would also unlock
 * a locked subject.
 */
public final class SdvRevocation {

    private SdvRevocation() {}

    /**
     * Clear {@code sdv_status} on an event CRF whose data just changed, if it
     * was verified. The bean is updated too, so a caller that saves it
     * afterwards does not write the flag back.
     *
     * @param ecb   the event CRF, as loaded before the change
     * @param actor who changed the data; recorded as {@code sdv_update_id}
     * @return {@code true} when the CRF was verified and no longer is
     */
    public static boolean revokeIfVerified(EventCRFBean ecb, UserAccountBean actor, EventCRFDAO dao) {
        if (ecb == null || !ecb.isSdvStatus()) {
            return false;
        }
        dao.setSDVStatus(false, actor.getId(), ecb.getId());
        ecb.setSdvStatus(false);
        ecb.setSdvUpdateId(actor.getId());
        return true;
    }
}
