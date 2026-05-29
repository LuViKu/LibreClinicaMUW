/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.managestudy;

import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.DiscrepancyNoteBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.ViewNotesDao;
import at.ac.meduniwien.ophthalmology.libreclinica.service.DiscrepancyNotesSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author Doug Rodrigues (douglas.rodrigues@openclinica.com)
 *
 */
public class ViewNotesServiceImpl implements ViewNotesService {

    private static final Logger LOG = LoggerFactory.getLogger(ViewNotesServiceImpl.class);

    private ViewNotesDao viewNotesDao;

    @Override
    public List<DiscrepancyNoteBean> listNotes(StudyBean currentStudy,
            ViewNotesFilterCriteria filter, ViewNotesSortCriteria sort) {
        List<DiscrepancyNoteBean> result = viewNotesDao.findAllDiscrepancyNotes(currentStudy, filter, sort);
        LOG.debug("Found " + result.size() + " discrepancy notes");
        return result;
    }

    @Override
    public DiscrepancyNotesSummary calculateNotesSummary(StudyBean currentStudy,
            ViewNotesFilterCriteria filter) {
        DiscrepancyNotesSummary result = viewNotesDao.calculateNotesSummary(currentStudy, filter);
        return result;
    }

    public ViewNotesDao getViewNotesDao() {
        return viewNotesDao;
    }

    public void setViewNotesDao(ViewNotesDao viewNotesDao) {
        this.viewNotesDao = viewNotesDao;
    }


}
