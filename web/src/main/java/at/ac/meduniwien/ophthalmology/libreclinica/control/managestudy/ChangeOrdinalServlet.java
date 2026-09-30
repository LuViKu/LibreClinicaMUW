/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy;

import jakarta.servlet.http.HttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.StudyTreeScope;

/**
 * A super class of ChangeDefinitionOrdinal and Change DefinitionCRFOrdinal
 * Servlets
 *
 * @author jxu
 */
@SuppressWarnings("all")
public abstract class ChangeOrdinalServlet extends SecureController {
    /**
	 * 
	 */
	private static final long serialVersionUID = -8449306173274291659L;

    private StudyTreeScope studyTreeScope;

	/**
     * Checks whether the user has the correct privilege
     */
    @Override
    public void mayProceed() throws InsufficientPermissionException {

        if (ub.isSysAdmin()) {
            mayMoveRequestedRecords();
            return;
        }

        if (currentRole.getRole().equals(Role.STUDYDIRECTOR) || currentRole.getRole().equals(Role.COORDINATOR)) {
            mayMoveRequestedRecords();
            return;
        }

        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.MENU_SERVLET, resexception.getString("not_study_director"), "1");

    }

    /**
     * The records the request moves, named by id, must be ones the current
     * study works with: the role checked above is the role in that study.
     */
    private void mayMoveRequestedRecords() throws InsufficientPermissionException {
        if (!inCurrentStudy(studyTreeScope(), new FormProcessor(request))) {
            addPageMessage(resexception.getString("not_select_valid_entity_current_study"));
            throw new InsufficientPermissionException(Page.MENU_SERVLET, resexception.getString("entity_not_belong_studies"), "1");
        }
    }

    /**
     * Whether every record the request names by id is one the current study
     * works with.
     */
    protected abstract boolean inCurrentStudy(StudyTreeScope scope, FormProcessor fp);

    private StudyTreeScope studyTreeScope() {
        if (studyTreeScope == null) {
            studyTreeScope = new StudyTreeScope(sm.getDataSource());
        }
        return studyTreeScope;
    }

    /** Moves an event definition, or a CRF within one, up or down the order: POST only. */
    @Override
    protected boolean acceptsGet(HttpServletRequest request) {
        return false;
    }

}
