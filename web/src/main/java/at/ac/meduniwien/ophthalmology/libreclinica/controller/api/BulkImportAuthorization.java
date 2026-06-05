/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

/**
 * Phase E.6 {@code bulk-import} — role gate for the ODM CRF-data
 * bulk-import surface
 * ({@code POST /pages/api/v1/import}, {@code POST /import/commit},
 * {@code GET /import/{token}/rows}).
 *
 * <p>Mirrors {@code ImportCRFDataServlet#mayProceed}: sysadmin always
 * passes; otherwise the operator's <em>current</em> role must be one
 * of Director, Coordinator, Investigator, RA, RA2. The legacy gate
 * doesn't tie the role to the active study (it relies on
 * {@code currentRole} already being scoped by session bootstrap), so
 * neither do we.
 *
 * <p>Why broader than {@link StudyAdminAuthorization#roleMayEditStudy}:
 * the bulk-import is an "operator-doing-their-job" action used by
 * site Investigators uploading ODM exports from external systems,
 * not just by Data Managers. The role list mirrors the legacy
 * servlet's gate exactly.
 */
final class BulkImportAuthorization {

    private BulkImportAuthorization() {}

    /**
     * @return {@code true} when {@code me} may upload + commit a CRF
     *         data import. Sysadmin always passes. Otherwise
     *         {@code currentRole} must be set and its role must be in
     *         {Director, Coordinator, Investigator, RA, RA2}.
     */
    static boolean roleMayImport(UserAccountBean me, StudyUserRoleBean currentRole) {
        if (me == null) return false;
        if (me.isSysAdmin()) return true;
        if (currentRole == null || currentRole.getRole() == null) return false;
        Role r = currentRole.getRole();
        return r == Role.STUDYDIRECTOR
                || r == Role.COORDINATOR
                || r == Role.INVESTIGATOR
                || r == Role.RESEARCHASSISTANT
                || r == Role.RESEARCHASSISTANT2;
    }
}
