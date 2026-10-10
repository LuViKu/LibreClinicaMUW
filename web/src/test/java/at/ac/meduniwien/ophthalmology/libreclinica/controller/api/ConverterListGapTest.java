package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

/**
 * Characterises gaps in the converter list of the {@code pages} dispatcher.
 *
 * <p>The list is {@code [ByteArray, Marshalling (JAXB), Jackson]}: no
 * {@code StringHttpMessageConverter} and no {@code ResourceHttpMessageConverter}.
 * So, as three real endpoints show:
 * <ul>
 *   <li>a {@code String} {@code @RequestPart} cannot be read (a browser sends a
 *       multipart text field with no content type, or {@code text/plain}):
 *       {@code CrfsApiController.uploadVersion};</li>
 *   <li>a {@code String} body with a non-JSON content type cannot be written:
 *       {@code StudyMetadataApiController.metadata} ({@code application/xml});</li>
 *   <li>a {@code Resource} body cannot be written:
 *       {@code EventCrfsApiController.downloadItemFile}.</li>
 * </ul>
 * Other controllers already write {@code byte[]} or stream to the response for
 * this reason (see the comments in {@code RetinalJobArtifactsApiController} and
 * {@code SubjectExportApiController}).
 *
 * <p>The three endpoints named above no longer depend on the missing converters:
 * they read the text fields as request parameters and write {@code byte[]}. The
 * tests here remain true and pin the reason: a new endpoint that takes a text
 * {@code @RequestPart} or returns a {@code String} or {@code Resource} body with a
 * non-JSON content type fails the same way. If the list gains the converters,
 * this test must flip. (Not a reason to add a global {@code StringHttpMessageConverter}:
 * it would change how every {@code @ResponseBody String} is written.)
 */
class ConverterListGapTest {

    @RestController
    static class Probe {
        @PostMapping("/part")
        String part(@RequestPart("name") String name) {
            return "got " + name;
        }

        @GetMapping("/xml-string")
        ResponseEntity<?> xmlString() {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body("<a/>");
        }

        @GetMapping("/resource")
        ResponseEntity<?> resource() {
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                    .body(new ByteArrayResource("abc".getBytes(StandardCharsets.UTF_8)));
        }
    }

    private MockMvc production() {
        return ProductionMvc.standalone(new Probe()).build();
    }

    private Exception resolvedForPart(String contentType) throws Exception {
        return production()
                .perform(multipart("/part").file(new MockMultipartFile("name", "", contentType, "v1".getBytes())))
                .andReturn().getResolvedException();
    }

    @Test
    void aTextPartWithoutContentTypeIsNotReadable() throws Exception {
        assertInstanceOf(HttpMediaTypeNotSupportedException.class, resolvedForPart(null));
    }

    @Test
    void aTextPlainPartIsNotReadable() throws Exception {
        assertInstanceOf(HttpMediaTypeNotSupportedException.class, resolvedForPart("text/plain"));
    }

    @Test
    void aStringBodyWithAnXmlContentTypeIsNotWritable() throws Exception {
        assertInstanceOf(HttpMessageNotWritableException.class,
                production().perform(get("/xml-string")).andReturn().getResolvedException());
    }

    @Test
    void aResourceBodyIsNotWritable() throws Exception {
        Exception e = production().perform(get("/resource")).andReturn().getResolvedException();
        assertInstanceOf(HttpMessageNotWritableException.class, e);
    }

}
