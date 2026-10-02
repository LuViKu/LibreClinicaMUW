/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.IdtViewDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.IdtView;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import org.apache.commons.dbcp.BasicDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
@RequestMapping(value = "auth/api/itemdata")
@ResponseStatus(value = org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
public class IdtViewController {
    @Autowired
    @Qualifier("dataSource")
    private BasicDataSource dataSource;

    @Autowired
    ServletContext context;

    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    IdtViewDao idtViewDao;

    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    StudyDAO sdao;

    @RequestMapping(value = "/sdv/{filternumber}/{studyoid}/paginated", params = { "page", "per_page" }, method = RequestMethod.GET)
    public ResponseEntity<List<IdtView>> getPaginatedIdtViewData(@PathVariable("filternumber") String filterNumber, @PathVariable("studyoid") String studyOid,
            @RequestParam("page") int page, @RequestParam("per_page") int per_page, HttpServletRequest request) throws Exception {
        ResourceBundleProvider.updateLocale(Locale.US);
        List<IdtView> idtDTO = null;
        if (page == 0) {
            page = 1;
        }
        if (per_page == 0) {
            per_page = 30; // default to 30 records / page
        }

        logger.debug("I'm in getPaginatedIdtViewData");

        HttpSession session = request.getSession(false);
        UserAccountBean user = session == null ? null : (UserAccountBean) session.getAttribute("userBean");
        if (user == null || user.getId() == 0) {
            return new ResponseEntity<List<IdtView>>(HttpStatus.UNAUTHORIZED);
        }
        StudyBean study = getStudy(studyOid);
        if (study == null || study.getId() == 0) {
            return new ResponseEntity<List<IdtView>>(HttpStatus.NOT_FOUND);
        }
        // The listing returns item data of every subject in the study (or
        // site); the caller needs a live role there. Until 2026-09 any API
        // key could read any study's.
        List<StudyUserRoleBean> roles = user.isSysAdmin() ? List.of() : new UserAccountDAO(dataSource).findAllRolesByUserName(user.getName());
        if (!mayViewStudy(user, study, roles)) {
            logger.warn("SDV item-data listing refused: user {} has no live role on study {}", user.getId(), study.getId());
            return new ResponseEntity<List<IdtView>>(HttpStatus.FORBIDDEN);
        }
        StudyBean parentStudy = getParentStudy(study);
        // int, not Integer: the heritage boxed ids compared by reference, so a
        // parent study above id 127 was queried as if it were a site.
        int pStudyId = parentStudy.getId();
        int studyId = study.getId();

        ArrayList<String> studySubjects = new ArrayList<>();
        // studySubjects.add("Sub B 101");
        // studySubjects.add("FIEL01");
        // studySubjects.add("104Waltham");
        // studySubjects.add("SS_SUBB101");

        ArrayList<String> studyEventDefinitions = new ArrayList<>();
        // studyEventDefinitions.add("SE_FOLLOWUPVISIT");

        ArrayList<String> crfs = new ArrayList<>();
        // crfs.add("Groups_Adverse_Events");

        int tagId = 1;
        int filter = Integer.valueOf(filterNumber);

        if (filter == 1) {
            idtDTO = getIdtViewDao().findFilter1(studyId, pStudyId, per_page, page, studySubjects, studyEventDefinitions, crfs, tagId,
                    studyScopeOperator(studyId, pStudyId));
        }
        return new ResponseEntity<List<IdtView>>(idtDTO, HttpStatus.OK);
    }

    /**
     * How the listing's query combines the study and parent-study conditions:
     * a parent study lists its own rows and its sites' ("OR"), a site only its
     * own ("AND").
     */
    static String studyScopeOperator(int studyId, int parentStudyId) {
        return studyId == parentStudyId ? "OR" : "AND";
    }

    /**
     * Whether {@code user} may list {@code study}'s item data: a system
     * administrator, or a user with an AVAILABLE role on the study itself or,
     * for a site, on its parent study. A role on a site alone does not open
     * the parent study.
     */
    static boolean mayViewStudy(UserAccountBean user, StudyBean study, List<StudyUserRoleBean> roles) {
        if (user == null || user.getId() == 0 || study == null || study.getId() == 0) {
            return false;
        }
        if (user.isSysAdmin()) {
            return true;
        }
        for (StudyUserRoleBean role : roles) {
            if (role == null || role.getStatus() == null || role.getStatus().getId() != Status.AVAILABLE.getId()) {
                continue;
            }
            if (role.getStudyId() == study.getId() || study.getParentStudyId() > 0 && role.getStudyId() == study.getParentStudyId()) {
                return true;
            }
        }
        return false;
    }

    private StudyBean getStudy(String oid) {
        sdao = new StudyDAO(dataSource);
        StudyBean studyBean = (StudyBean) sdao.findByOid(oid);
        return studyBean;
    }

    private StudyBean getParentStudy(StudyBean study) {
        if (study.getParentStudyId() == 0) {
            return study;
        } else {
            return (StudyBean) new StudyDAO(dataSource).findByPK(study.getParentStudyId());
        }
    }

    public IdtViewDao getIdtViewDao() {
        return idtViewDao;
    }

}
