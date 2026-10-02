/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.web.filter.StudyTreeScope;

/**
 * Processes request to change ordinals of study event definitions in a study
 *
 * @author jxu
 */
@SuppressWarnings("all")
public class ChangeDefinitionOrdinalServlet extends ChangeOrdinalServlet {

    /**
	 * 
	 */
	private static final long serialVersionUID = -357106030206216135L;

	@Override
    public void processRequest() throws Exception {
        FormProcessor fp = new FormProcessor(request);
        int current = fp.getInt("current");
        int previous = fp.getInt("previous");
        StudyEventDefinitionDAO seddao = new StudyEventDefinitionDAO(sm.getDataSource());
        increase(current, previous, seddao);
        String url=response.encodeRedirectURL("ListEventDefinition");
        response.sendRedirect(url);
//        forwardPage(Page.LIST_DEFINITION_SERVLET);

    }

    /** The two event definitions swapped must be ones the current study schedules from. */
    @Override
    protected boolean inCurrentStudy(StudyTreeScope scope, FormProcessor fp) {
        return definitionInStudy(scope, fp.getInt("current")) && definitionInStudy(scope, fp.getInt("previous"));
    }

    private boolean definitionInStudy(StudyTreeScope scope, int definitionId) {
        return definitionId <= 0 || scope.containsEventDefinition(currentStudy, definitionId);
    }

    /**
     * increase the ordinal for current object and decrease the ordinal of the
     * previous one
     *
     * @param idCurrent
     * @param idPrevious
     */
    private void increase(int idCurrent, int idPrevious, StudyEventDefinitionDAO dao) {

        if (idCurrent > 0) {
            StudyEventDefinitionBean current = (StudyEventDefinitionBean) dao.findByPK(idCurrent);

            int currentOrdinal = current.getOrdinal();
            current.setOrdinal(currentOrdinal - 1);
            dao.update(current);
        }
        if (idPrevious > 0) {
            StudyEventDefinitionBean previous = (StudyEventDefinitionBean) dao.findByPK(idPrevious);
            int previousOrdinal = previous.getOrdinal();
            previous.setOrdinal(previousOrdinal + 1);

            dao.update(previous);
        }

    }

}