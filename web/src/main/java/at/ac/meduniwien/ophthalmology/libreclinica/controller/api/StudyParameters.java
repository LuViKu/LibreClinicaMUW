/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.service.StudyParameterConfig;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.service.StudyParameterValueBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.service.StudyParameterValueDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A study parameter as the legacy servlets see it for the session's study.
 *
 * <p>Legacy data entry and subject registration read their switches from the
 * session study's {@link StudyParameterConfig}, which {@code SecureController}
 * builds with {@code StudyConfigService}: the parameter's default, then the
 * study's own value; for a site, the parent study's value and then the
 * site's. The SPA binds the session study with {@code StudyDAO} alone
 * ({@code POST /me/activeStudy}), so the config it carries holds nothing but
 * the defaults. This reads the values the legacy config would hold, for one
 * handle, from the database.
 *
 * <p>The defaults are {@link StudyParameterConfig}'s, which is what legacy
 * falls back to; {@code study_parameter.default_value} is not, and for some
 * handles it is not even a legal value.
 */
final class StudyParameters {

    private static final Logger LOG = LoggerFactory.getLogger(StudyParameters.class);

    static final String ADMIN_FORCED_REASON_FOR_CHANGE = "adminForcedReasonForChange";
    static final String COLLECT_DOB = "collectDob";
    static final String GENDER_REQUIRED = "genderRequired";
    static final String SUBJECT_PERSON_ID_REQUIRED = "subjectPersonIdRequired";

    private StudyParameters() {}

    /**
     * The value of {@code handle} for {@code study}: the study's own value; for
     * a site, else its parent's; else {@code fallback}. A blank value counts as
     * none.
     */
    static String value(DataSource dataSource, StudyBean study, String handle, String fallback) {
        if (study == null || study.getId() <= 0) {
            return fallback;
        }
        try {
            StudyParameterValueDAO dao = new StudyParameterValueDAO(dataSource);
            String own = stored(dao, study.getId(), handle);
            if (own != null) {
                return own;
            }
            if (study.getParentStudyId() > 0) {
                String parent = stored(dao, study.getParentStudyId(), handle);
                if (parent != null) {
                    return parent;
                }
            }
        } catch (RuntimeException e) {
            // The legacy defaults are the strict settings (a reason is
            // forced, the identifiers are required), so an unreadable
            // value fails closed.
            LOG.warn("Study parameter {} of study {} could not be read, using the default: {}",
                    handle, study.getId(), e.getMessage());
        }
        return fallback;
    }

    /** {@code adminForcedReasonForChange}; legacy compares it with "true". */
    static boolean adminForcedReasonForChange(DataSource dataSource, StudyBean study) {
        return "true".equals(value(dataSource, study, ADMIN_FORCED_REASON_FOR_CHANGE,
                new StudyParameterConfig().getAdminForcedReasonForChange()));
    }

    private static String stored(StudyParameterValueDAO dao, int studyId, String handle) {
        StudyParameterValueBean row = dao.findByHandleAndStudy(studyId, handle);
        if (row == null || row.getId() <= 0 || row.getValue() == null || row.getValue().isBlank()) {
            return null;
        }
        return row.getValue().trim();
    }
}
