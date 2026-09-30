/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.time.LocalDate;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code GET /api/v1/studies/{oid}/metadata}: the study's CDISC ODM 1.3
 * metadata as an XML download, for the archive at study close. The SPA
 * replacement of the legacy {@code DownloadStudyMetadataServlet}; the
 * document is generated the same way ({@link StudyMetadataOdm}).
 *
 * <p>Access is the servlet's ({@link
 * StudyAdminAuthorization#userMayViewStudyDesign}): a system
 * administrator, or anyone whose active role on the study, or on its
 * parent when the study is a site, may view the study's data: study
 * director, coordinator, investigator, data entry person (both kinds)
 * and monitor. The document is the study's design and its
 * administrative data; it holds no subject data. Keeping the servlet's
 * gate means its page can be retired for every role that uses it.
 */
@RestController
@RequestMapping("/api/v1/studies")
@Tag(name = "Studies", description = "User's available studies.")
public class StudyMetadataApiController {

    private static final Logger LOG = LoggerFactory.getLogger(StudyMetadataApiController.class);

    private final DataSource dataSource;
    private final RuleSetRuleDao ruleSetRuleDao;
    private final CoreResources coreResources;

    @Autowired
    public StudyMetadataApiController(@Qualifier("dataSource") DataSource dataSource,
                                      RuleSetRuleDao ruleSetRuleDao,
                                      CoreResources coreResources) {
        this.dataSource = dataSource;
        this.ruleSetRuleDao = ruleSetRuleDao;
        this.coreResources = coreResources;
    }

    @GetMapping("/{studyOid}/metadata")
    @ApiResponse(responseCode = "200",
                 content = @Content(mediaType = "application/xml", schema = @Schema(type = "string")))
    public ResponseEntity<?> metadata(@PathVariable("studyOid") String studyOid, HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean study = new StudyDAO(dataSource).findByOid(studyOid);
        if (study == null || study.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }
        if (!StudyAdminAuthorization.userMayViewStudyDesign(me, study, dataSource)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit downloading this study's metadata"));
        }

        String xml = StudyMetadataOdm.xml(dataSource, study, ruleSetRuleDao, coreResources);
        LOG.info("Study metadata download: oid={} by user={}", study.getOid(), me.getName());
        String fileStem = study.getOid().replaceAll("[^A-Za-z0-9_.-]", "_");
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "xml", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileStem + "_metadata_" + LocalDate.now() + ".xml\"")
                .body(xml);
    }
}
