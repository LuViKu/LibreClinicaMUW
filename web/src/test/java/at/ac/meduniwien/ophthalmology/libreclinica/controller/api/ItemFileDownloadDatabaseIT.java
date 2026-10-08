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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.CrfFileStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crf.EventCrfPresenceRegistry;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;

/**
 * {@code GET /api/v1/eventCrfs/{id}/items/{itemOid}/file} writes the stored
 * file through the converters the {@code pages} dispatcher really has, which
 * include no {@code ResourceHttpMessageConverter}: a {@code Resource} body
 * answered 500 there (see {@code ConverterListGapTest}).
 */
class ItemFileDownloadDatabaseIT extends AbstractApiControllerDatabaseIT {

    @TempDir
    static Path attachments;

    private MockMvc mvc() {
        CrfFileStorageService storage = new CrfFileStorageService() {
            @Override
            public Path baseDir() {
                return attachments;
            }
        };
        return PagesDispatcherMvc.standalone(new EventCrfsApiController(DATA_SOURCE,
                        new SiteVisibilityFilter(DATA_SOURCE), storage, new EventCrfPresenceRegistry(),
                        new RetinalResultItemDataPopulator(DATA_SOURCE)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void theUploadedFileComesBackByteForByte() throws Exception {
        byte[] content = new byte[] {'%', 'P', 'D', 'F', '-', '1', '.', '4', 0, (byte) 0xFF, 10, 13, (byte) 0x80};
        var session = ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_investigator");
        String url = "/api/v1/eventCrfs/5/items/I_CONSENT_DATE/file";

        mvc().perform(multipart(url).file(new MockMultipartFile("file", "scan.pdf", "application/pdf", content))
                        .session(session))
                .andExpect(status().isOk());

        byte[] downloaded = mvc().perform(get(url).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/octet-stream")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("scan.pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(content, downloaded);
    }
}
