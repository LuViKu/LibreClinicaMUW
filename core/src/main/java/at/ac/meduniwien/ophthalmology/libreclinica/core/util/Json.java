package at.ac.meduniwien.ophthalmology.libreclinica.core.util;

import java.util.Objects;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The application's JSON configuration, in one place.
 *
 * <p>The application moved from Jackson 2 to Jackson 3, whose defaults differ
 * from Jackson 2 in ways that change bytes on the wire and in the database
 * (property order, date format, null handling, trailing input, enum
 * naming ...). Everything here is configured so that what the SPA receives
 * and what is stored stay exactly what Jackson 2 produced and accepted. The
 * table of features, the Jackson 3 default and what is set instead, is in
 * {@code docs/development/modernization/spring-boot-4-jackson-3.md} and is
 * asserted by {@code JsonTest}.
 *
 * <h2>Two mappers</h2>
 * <ul>
 *   <li>{@link #mapper()}: what the {@code pages} dispatcher's converter,
 *       the legacy JSON servlets and every writer of stored JSON use.
 *       Unknown properties in what is read are ignored, as Spring's
 *       converter always did.</li>
 *   <li>{@link #strict()}: identical, except that unknown properties fail the
 *       read. A plain Jackson 2 {@code ObjectMapper} did this, and the two
 *       readers that bind stored JSON to records (the CRF item terminology
 *       {@code fill_map}) rely on it: a malformed map falls back to
 *       "system only" rather than half-loading.</li>
 * </ul>
 *
 * <p>Both are immutable once built and safe to share; do not construct
 * another {@code JsonMapper}.
 */
public final class Json {

    private static final JsonMapper MAPPER = base().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private static final JsonMapper STRICT = base().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private Json() {}

    /** The shared application mapper; unknown properties on input are ignored. */
    public static JsonMapper mapper() {
        return MAPPER;
    }

    /** As {@link #mapper()} but unknown properties on input fail the read. */
    public static JsonMapper strict() {
        return STRICT;
    }

    private static JsonMapper.Builder base() {
        // Token level, Jackson's own switch back to the Jackson 2 defaults: a
        // character outside the Basic Multilingual Plane is written as an
        // escaped surrogate pair rather than raw UTF-8 (same JSON, different
        // bytes, and stored JSON is compared as bytes), "/" is not escaped,
        // and the fast number parsers stay off.
        JsonFactory tokens = JsonFactory.builderWithJackson2Defaults().build();
        return JsonMapper.builder(tokens)
                // Data-binding level, likewise: dates and durations as
                // timestamps, null primitives and trailing tokens tolerated,
                // properties in declaration order, enums by name(), empty
                // beans fail, getters-as-setters and final-field mutators on,
                // no parameter-name detection.
                .configureForJackson2()
                // Spring's Jackson 2 converter did this; Jackson 3 already does.
                .disable(MapperFeature.DEFAULT_VIEW_INCLUSION);
    }

    /**
     * A {@link RestTemplate} whose JSON converter is Jackson 3 on {@link #mapper()},
     * stated rather than left to classpath detection.
     */
    public static RestTemplate restTemplate(ClientHttpRequestFactory requestFactory) {
        Objects.requireNonNull(requestFactory, "requestFactory");
        RestTemplate rest = new RestTemplate(HttpMessageConverters.forClient()
                .registerDefaults()
                .withJsonConverter(new JacksonJsonHttpMessageConverter(MAPPER))
                .build());
        rest.setRequestFactory(requestFactory);
        return rest;
    }

    /**
     * The text of a node as Jackson 2's {@code JsonNode.asText()} gave it:
     * strings as they are, numbers and booleans in their JSON form,
     * {@code "null"} for a JSON null, and the empty string for anything else
     * (a missing node, an object, an array). Jackson 3's {@code asString()}
     * throws on the last group.
     */
    public static String text(JsonNode node) {
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asString();
        }
        return node.isNull() ? "null" : "";
    }
}
