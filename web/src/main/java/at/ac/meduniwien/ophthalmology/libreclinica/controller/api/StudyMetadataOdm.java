/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.ArrayList;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.odm.FullReportBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.odmbeans.ODMBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.odmExport.AdminDataCollector;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.odmExport.MetaDataCollector;

/**
 * The CDISC ODM 1.3 study-metadata document of one study: its metadata
 * (protocol, event definitions, forms, item groups, items, code lists)
 * and administrative data, with the OpenClinica extensions.
 *
 * <p>The generation is {@code DownloadStudyMetadataServlet.processRequest}
 * step for step, so the SPA download and the legacy one produce the same
 * document for the same study; {@code StudyMetadataApiControllerDatabaseIT}
 * compares the two. The servlet itself is left as it is until its wave is
 * retired. The only difference is the study: the servlet always exports
 * the session's current study, this exports the one it is given.
 */
final class StudyMetadataOdm {

    private StudyMetadataOdm() {}

    static String xml(DataSource dataSource, StudyBean study,
                      RuleSetRuleDao ruleSetRuleDao, CoreResources coreResources) {
        MetaDataCollector mdc = new MetaDataCollector(dataSource, study, ruleSetRuleDao);
        AdminDataCollector adc = new AdminDataCollector(dataSource, study);
        MetaDataCollector.setTextLength(200);

        ODMBean odmb = mdc.getODMBean();
        odmb.setSchemaLocation("http://www.cdisc.org/ns/odm/v1.3 OpenClinica-ODM1-3-0-OC2-0.xsd");
        ArrayList<String> xmlnsList = new ArrayList<>();
        xmlnsList.add("xmlns=\"http://www.cdisc.org/ns/odm/v1.3\"");
        xmlnsList.add("xmlns:OpenClinica=\"http://www.openclinica.org/ns/odm_ext_v130/v3.1\"");
        xmlnsList.add("xmlns:OpenClinicaRules=\"http://www.openclinica.org/ns/rules/v3.1\"");
        odmb.setXmlnsList(xmlnsList);
        odmb.setODMVersion("oc1.3");
        mdc.setODMBean(odmb);
        adc.setOdmbean(odmb);
        mdc.collectFileData();
        adc.collectFileData();

        FullReportBean report = new FullReportBean();
        report.setAdminDataMap(adc.getOdmAdminDataMap());
        report.setOdmStudyMap(mdc.getOdmStudyMap());
        report.setCoreResources(coreResources);
        report.setOdmBean(mdc.getODMBean());
        report.setODMVersion("oc1.3");
        report.createStudyMetaOdmXml(Boolean.FALSE);
        return report.getXmlOutput().toString().trim();
    }
}
