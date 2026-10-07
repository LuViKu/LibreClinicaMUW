package at.ac.meduniwien.ophthalmology.libreclinica.core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The feature table of {@link Json}, asserted. Each line is a feature whose
 * Jackson 3 default differs from what Jackson 2 did (or that the application
 * depends on), set to the Jackson 2 value. The table with the Jackson 3
 * defaults is in docs/development/modernization/spring-boot-4-jackson-3.md;
 * a failure here after a library bump means a default moved under us.
 */
public class JsonTest {

    private static void check(JsonMapper m, boolean unknownFails) {
        // --- bytes written: dates, order, enums, inclusion ---
        assertTrue("WRITE_DATES_AS_TIMESTAMPS (J3 default false)", m.isEnabled(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS));
        assertTrue("WRITE_DURATIONS_AS_TIMESTAMPS (J3 default false)", m.isEnabled(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS));
        assertTrue("WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS", m.isEnabled(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS));
        assertFalse("WRITE_DATE_KEYS_AS_TIMESTAMPS", m.isEnabled(DateTimeFeature.WRITE_DATE_KEYS_AS_TIMESTAMPS));
        assertFalse("ONE_BASED_MONTHS (J3 default true)", m.isEnabled(DateTimeFeature.ONE_BASED_MONTHS));
        assertFalse("SORT_PROPERTIES_ALPHABETICALLY (J3 default true)", m.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
        assertFalse("ORDER_MAP_ENTRIES_BY_KEYS", m.isEnabled(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
        assertFalse("WRITE_ENUMS_USING_TO_STRING (J3 default true)", m.isEnabled(EnumFeature.WRITE_ENUMS_USING_TO_STRING));
        assertFalse("READ_ENUMS_USING_TO_STRING (J3 default true)", m.isEnabled(EnumFeature.READ_ENUMS_USING_TO_STRING));
        assertFalse("WRITE_ENUMS_USING_INDEX", m.isEnabled(EnumFeature.WRITE_ENUMS_USING_INDEX));
        assertTrue("FAIL_ON_EMPTY_BEANS (J3 default false)", m.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS));
        assertFalse("INDENT_OUTPUT", m.isEnabled(SerializationFeature.INDENT_OUTPUT));
        assertFalse("WRAP_ROOT_VALUE", m.isEnabled(SerializationFeature.WRAP_ROOT_VALUE));
        assertTrue("WRITE_EMPTY_JSON_ARRAYS", m.isEnabled(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS));
        assertFalse("WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED", m.isEnabled(SerializationFeature.WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED));
        assertFalse("DEFAULT_VIEW_INCLUSION", m.isEnabled(MapperFeature.DEFAULT_VIEW_INCLUSION));
        assertFalse("FIX_FIELD_NAME_UPPER_CASE_PREFIX (J3 default true)", m.isEnabled(MapperFeature.FIX_FIELD_NAME_UPPER_CASE_PREFIX));

        // --- bytes written: token level ---
        assertFalse("COMBINE_UNICODE_SURROGATES_IN_UTF8 (J3 default true)", m.isEnabled(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8));
        assertFalse("ESCAPE_FORWARD_SLASHES", m.isEnabled(JsonWriteFeature.ESCAPE_FORWARD_SLASHES));
        assertFalse("ESCAPE_NON_ASCII", m.isEnabled(JsonWriteFeature.ESCAPE_NON_ASCII));
        assertTrue("WRITE_NAN_AS_STRINGS", m.isEnabled(JsonWriteFeature.WRITE_NAN_AS_STRINGS));
        assertFalse("WRITE_BIGDECIMAL_AS_PLAIN", m.isEnabled(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN));
        assertFalse("USE_FAST_DOUBLE_WRITER", m.isEnabled(StreamWriteFeature.USE_FAST_DOUBLE_WRITER));

        // --- bytes accepted ---
        assertEquals("FAIL_ON_UNKNOWN_PROPERTIES", unknownFails, m.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        assertFalse("FAIL_ON_NULL_FOR_PRIMITIVES (J3 default true)", m.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES));
        assertFalse("FAIL_ON_TRAILING_TOKENS (J3 default true)", m.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
        assertTrue("ACCEPT_FLOAT_AS_INT", m.isEnabled(DeserializationFeature.ACCEPT_FLOAT_AS_INT));
        assertTrue("ALLOW_COERCION_OF_SCALARS", m.isEnabled(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        assertFalse("ACCEPT_SINGLE_VALUE_AS_ARRAY", m.isEnabled(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY));
        assertFalse("UNWRAP_SINGLE_VALUE_ARRAYS", m.isEnabled(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS));
        assertFalse("USE_BIG_DECIMAL_FOR_FLOATS", m.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        assertFalse("FAIL_ON_READING_DUP_TREE_KEY", m.isEnabled(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY));
        assertFalse("ACCEPT_CASE_INSENSITIVE_PROPERTIES", m.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES));
        assertFalse("ACCEPT_CASE_INSENSITIVE_ENUMS", m.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS));
        assertFalse("READ_UNKNOWN_ENUM_VALUES_AS_NULL", m.isEnabled(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL));
        assertTrue("STRIP_TRAILING_BIGDECIMAL_ZEROES (J3 default false)", m.isEnabled(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES));
        assertFalse("USE_FAST_DOUBLE_PARSER (J3 default true)", m.isEnabled(StreamReadFeature.USE_FAST_DOUBLE_PARSER));
        assertFalse("USE_FAST_BIG_NUMBER_PARSER (J3 default true)", m.isEnabled(StreamReadFeature.USE_FAST_BIG_NUMBER_PARSER));
        assertFalse("INCLUDE_SOURCE_IN_LOCATION", m.isEnabled(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION));

        // --- how plain beans are bound (request records are unaffected) ---
        assertTrue("USE_GETTERS_AS_SETTERS (J3 default false)", m.isEnabled(MapperFeature.USE_GETTERS_AS_SETTERS));
        assertTrue("ALLOW_FINAL_FIELDS_AS_MUTATORS (J3 default false)", m.isEnabled(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS));
        assertFalse("DETECT_PARAMETER_NAMES (J3 default true)", m.isEnabled(MapperFeature.DETECT_PARAMETER_NAMES));

        // --- streams ---
        assertTrue("AUTO_CLOSE_TARGET", m.isEnabled(StreamWriteFeature.AUTO_CLOSE_TARGET));
        assertTrue("FLUSH_PASSED_TO_STREAM", m.isEnabled(StreamWriteFeature.FLUSH_PASSED_TO_STREAM));
        assertTrue("AUTO_CLOSE_SOURCE", m.isEnabled(StreamReadFeature.AUTO_CLOSE_SOURCE));
    }

    @Test
    public void theSharedMapperIsOnJackson2Behaviour() {
        check(Json.mapper(), false);
    }

    @Test
    public void theStrictMapperDiffersOnlyInFailingOnUnknownProperties() {
        check(Json.strict(), true);
    }

    @Test
    public void bothMappersAreSharedInstances() {
        assertSame(Json.mapper(), Json.mapper());
        assertSame(Json.strict(), Json.strict());
    }

    @Test
    public void textIsWhatJackson2AsTextWas() {
        JsonNode n = Json.mapper().readTree(
                "{\"s\":\"x\",\"i\":7,\"d\":1.5,\"b\":true,\"n\":null,\"o\":{\"a\":1},\"a\":[1]}");
        assertEquals("x", Json.text(n.get("s")));
        assertEquals("7", Json.text(n.get("i")));
        assertEquals("1.5", Json.text(n.get("d")));
        assertEquals("true", Json.text(n.get("b")));
        assertEquals("null", Json.text(n.get("n")));
        assertEquals("", Json.text(n.get("o")));
        assertEquals("", Json.text(n.get("a")));
        assertEquals("", Json.text(n.path("missing")));
    }

    @Test
    public void aBlankOrEmptyDocumentReadsAsAMissingNodeNotAnException() {
        // The clients (Remidio, DICOM describe, cluster health) treat "no object" as
        // "not usable" and rely on the read not throwing for an empty body.
        assertTrue(Json.mapper().readTree("").isMissingNode());
        assertTrue(Json.mapper().readTree(new byte[0]).isMissingNode());
    }

    @Test
    public void restTemplateJsonConverterIsJackson3OnTheSharedMapper() {
        RestTemplate rest = Json.restTemplate(new SimpleClientHttpRequestFactory());
        JacksonJsonHttpMessageConverter json = null;
        for (HttpMessageConverter<?> c : rest.getMessageConverters()) {
            if (c instanceof JacksonJsonHttpMessageConverter j) {
                assertNull("only one Jackson converter", json);
                json = j;
            }
        }
        assertNotNull("a Jackson 3 JSON converter is registered", json);
        assertSame(Json.mapper(), json.getMapper());
    }

    @Test
    public void mapsAndListsKeepInsertionOrder() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("z", 1);
        m.put("a", List.of(2, 1));
        assertEquals("{\"z\":1,\"a\":[2,1]}", Json.mapper().writeValueAsString(m));
    }
}
