package at.ac.meduniwien.ophthalmology.libreclinica.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

import at.ac.meduniwien.ophthalmology.libreclinica.webmvc.WebMvcConfig;

/**
 * MockMvc built on the message converters production uses.
 *
 * <p>{@code standaloneSetup} alone registers MockMvc's own default converters,
 * so a test would pass against JSON behaviour the application never runs with
 * (unknown-property handling, null-for-primitive, date and number formats). The
 * converter list here comes from {@link WebMvcConfig#apiMessageConverters} and
 * the {@code jacksonMessageConverter} bean method, the same code the
 * {@code pages} dispatcher is wired from.
 *
 * <p>Like production it has no {@code StringHttpMessageConverter} and no
 * {@code ResourceHttpMessageConverter}: a {@code String} response body is
 * written as a JSON string, and one with a non-JSON content type is refused.
 */
public final class ProductionMvc {

    private ProductionMvc() {}

    /** The production converter list, in production order. */
    public static List<HttpMessageConverter<?>> converters() {
        WebMvcConfig cfg = new WebMvcConfig();
        return WebMvcConfig.apiMessageConverters(
                cfg.marshallingHttpMessageConverter(cfg.jaxbMarshaller()),
                cfg.jacksonMessageConverter());
    }

    /**
     * Read a JSON request body the way the {@code pages} dispatcher does: the
     * first production converter that can read {@code type} as
     * {@code application/json}.
     */
    @SuppressWarnings("unchecked")
    public static <T> T read(Class<T> type, String json) throws java.io.IOException {
        MediaType json_ = MediaType.APPLICATION_JSON;
        for (HttpMessageConverter<?> c : converters()) {
            if (c.canRead(type, json_)) {
                MockHttpInputMessage in = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
                in.getHeaders().setContentType(json_);
                return (T) ((HttpMessageConverter<Object>) c).read((Class<Object>) type, in);
            }
        }
        throw new IllegalStateException("no production converter reads " + type);
    }

    /**
     * Standalone builder with the production converters. Advice is the caller's
     * to add ({@code .setControllerAdvice(new ApiExceptionHandler())}), as it
     * was with {@code MockMvcBuilders.standaloneSetup}.
     */
    public static StandaloneMockMvcBuilder standalone(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .setMessageConverters(converters().toArray(new HttpMessageConverter<?>[0]));
    }
}
