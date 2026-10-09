/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * {@code GET /api/v1/studies/{oid}/metadata} answers 200 with the ODM document
 * through the converters the {@code pages} dispatcher really has, which include
 * no {@code StringHttpMessageConverter}: a {@code String} body with an XML
 * content type answered 500 there (see {@code ConverterListGapTest}). The
 * document's content and the access gate are
 * {@code StudyMetadataApiControllerDatabaseIT}'s.
 */
class StudyMetadataDownloadDatabaseIT extends AbstractApiControllerDatabaseIT {

    @Test
    void theDocumentIsWrittenAsUtf8Xml() throws Exception {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean root = new UserAccountBean();
        root.setId(9801);
        root.setName("root");
        root.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", root);

        PagesDispatcherMvc.standalone(new StudyMetadataApiController(DATA_SOURCE,
                        Mockito.mock(RuleSetRuleDao.class), Mockito.mock(CoreResources.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(get("/api/v1/studies/S_DEFAULTS1/metadata").session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/xml")))
                .andExpect(header().string("Content-Type", containsString("UTF-8")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().string(containsString("<Study OID=\"S_DEFAULTS1\"")));
    }
}
