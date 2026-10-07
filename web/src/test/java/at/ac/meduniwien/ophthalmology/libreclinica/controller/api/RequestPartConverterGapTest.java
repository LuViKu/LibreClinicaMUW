package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

/**
 * Characterises a gap in the converter list of the {@code pages} dispatcher:
 * [ByteArray, Marshalling, Jackson] has no converter for a {@code String}
 * {@code @RequestPart}, so a multipart text field (no content type, or
 * {@code text/plain}, which is what a browser sends) is refused with
 * {@code HttpMediaTypeNotSupportedException}. Controllers with such a part
 * ({@code CrfsApiController.uploadVersion}) are therefore untestable through
 * {@link ProductionMvc#standalone} alone; their unit tests use
 * {@link ProductionMvc#standaloneWithStringParts}. If the list gains a
 * {@code StringHttpMessageConverter} this test must flip, and that helper go.
 */
class RequestPartConverterGapTest {

    @RestController
    static class Probe {
        @PostMapping("/p")
        String p(@RequestPart("name") String name) {
            return "got " + name;
        }
    }

    private Exception resolved(String contentType) throws Exception {
        MockMvc mvc = ProductionMvc.standalone(new Probe()).build();
        return mvc.perform(multipart("/p").file(new MockMultipartFile("name", "", contentType, "v1".getBytes())))
                .andReturn().getResolvedException();
    }

    @Test
    void aTextPartWithoutContentTypeIsNotReadable() throws Exception {
        assertInstanceOf(HttpMediaTypeNotSupportedException.class, resolved(null));
    }

    @Test
    void aTextPlainPartIsNotReadable() throws Exception {
        assertInstanceOf(HttpMediaTypeNotSupportedException.class, resolved("text/plain"));
    }
}
