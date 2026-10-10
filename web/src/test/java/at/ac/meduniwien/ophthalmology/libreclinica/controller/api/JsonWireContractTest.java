package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

/**
 * The SPA's JSON wire contract, asserted against the converters the
 * {@code pages} dispatcher really uses ({@link ProductionMvc}), not against
 * MockMvc's defaults.
 *
 * <p>Every expectation below is what the application did on Jackson 2 before
 * the Jackson 3 move, recorded from a run of this class on that code. A
 * failure here after a library change means the wire format moved: decide
 * whether the SPA tolerates it, then either restore the old behaviour in
 * {@code Json} or change the expectation on purpose and say so in the commit.
 *
 * <p>The probe controller returns and accepts the real DTO types the API
 * uses, so the records' own shapes (property order, {@code @JsonInclude},
 * {@code BigDecimal} bounds, primitive components) are what is pinned.
 */
class JsonWireContractTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ProductionMvc.standalone(new Probe())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private String fetch(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String send(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /* ------------------------------------------------------------------ */
    /* response: content type, dates, numbers, text                        */
    /* ------------------------------------------------------------------ */

    @Test
    void jsonIsServedAsPlainApplicationJson() throws Exception {
        mvc.perform(get("/contract/order-map"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"));
    }

    @Test
    void datesInAMapAndInARecordKeepTheirTodaysFormats() throws Exception {
        // java.util.Date is epoch millis; java.time follows
        // WRITE_DATES_AS_TIMESTAMPS (numeric array / decimal seconds), not ISO text.
        assertEquals(DATES_MAP, fetch("/contract/dates-map"));
        assertEquals(DATES_RECORD, fetch("/contract/dates-record"));
    }

    @Test
    void numbersKeepTheirTextualForm() throws Exception {
        assertEquals(NUMBERS, fetch("/contract/numbers"));
    }

    @Test
    void textIsUtf8AndNotEscapedBeyondTheJsonMinimum() throws Exception {
        assertEquals(TEXT, fetch("/contract/text"));
    }

    /* ------------------------------------------------------------------ */
    /* response: property order, inclusion, BigDecimal                     */
    /* ------------------------------------------------------------------ */

    @Test
    void linkedHashMapKeepsInsertionOrderAndWritesNullValues() throws Exception {
        assertEquals("{\"zeta\":1,\"alpha\":null,\"mid\":{\"b\":2,\"a\":1},\"list\":[3,1,2]}",
                fetch("/contract/order-map"));
    }

    @Test
    void aRecordDtoKeepsComponentOrder() throws Exception {
        assertEquals(CATALOG, fetch("/contract/catalog"));
    }

    @Test
    void aPojoKeepsDeclarationOrderNotAlphabetical() throws Exception {
        assertEquals(POJO, fetch("/contract/pojo"));
    }

    @Test
    void nonNullDtosOmitNullComponents() throws Exception {
        assertEquals("{\"id\":\"7\",\"username\":\"u\",\"active\":true,\"locked\":false}",
                fetch("/contract/non-null"));
        assertEquals("{\"id\":\"1\",\"occurredAt\":\"2026-01-02T03:04:05Z\"}",
                fetch("/contract/non-null-audit"));
    }

    @Test
    void bigDecimalIsAJsonNumberNotAString() throws Exception {
        String body = fetch("/contract/catalog");
        assertTrue(body.contains("\"minValue\":0.5,\"maxValue\":1000,\"stepValue\":0.10"), body);
    }

    /* ------------------------------------------------------------------ */
    /* request: null / missing primitives, unknown properties, trailing    */
    /* ------------------------------------------------------------------ */

    @Test
    void nullAndMissingPrimitivesBecomeTheirDefault() throws Exception {
        // FAIL_ON_NULL_FOR_PRIMITIVES is off: a missing or null primitive is
        // 0 / false, never a 400.
        assertEquals("{\"dryRun\":false,\"sedOids\":[\"SE_A\"]}",
                send("/contract/migrate", "{\"sedOids\":[\"SE_A\"],\"dryRun\":null}"));
        assertEquals("{\"dryRun\":false,\"sedOids\":null}", send("/contract/migrate", "{}"));
        assertEquals("{\"dryRun\":true,\"sedOids\":[]}",
                send("/contract/migrate", "{\"sedOids\":[],\"dryRun\":true}"));
        assertEquals("{\"eventCrfId\":0}", send("/contract/bind", "{\"eventCrfId\":null}"));
        assertEquals("{\"eventCrfId\":0}", send("/contract/bind", "{}"));
        assertEquals("{\"eventCrfId\":12}", send("/contract/bind", "{\"eventCrfId\":12}"));
        assertEquals("{\"jobIds\":[4,5],\"eventCrfId\":0}",
                send("/contract/bulk-bind", "{\"jobIds\":[4,5],\"eventCrfId\":null}"));
    }

    @Test
    void unknownRequestPropertiesAreIgnored() throws Exception {
        assertEquals("{\"dryRun\":true,\"sedOids\":[\"SE_A\"]}",
                send("/contract/migrate",
                        "{\"sedOids\":[\"SE_A\"],\"dryRun\":true,\"extra\":{\"x\":[1,2]},\"more\":1}"));
        assertEquals("{\"eventCrfId\":3}",
                send("/contract/bind", "{\"eventCrfId\":3,\"unexpected\":\"yes\"}"));
    }

    @Test
    void trailingTokensAfterTheValueAreTolerated() throws Exception {
        assertEquals("{\"eventCrfId\":3}", send("/contract/bind", "{\"eventCrfId\":3} {\"eventCrfId\":9}"));
        assertEquals("{\"eventCrfId\":3}", send("/contract/bind", "{\"eventCrfId\":3} garbage"));
    }

    @Test
    void scalarCoercionOnRequestsIsAsItWas() throws Exception {
        assertEquals(COERCE_OK, send("/contract/coerce",
                "{\"n\":\"12\",\"boxed\":\"\",\"l\":7.9,\"d\":\"1.5\",\"b\":\"true\",\"s\":42,\"dec\":\"2.50\"}"));
        assertEquals(COERCE_EMPTY, send("/contract/coerce", "{\"n\":\"\",\"s\":\"\",\"b\":null}"));
    }

    @Test
    void untypedRequestBodiesComeBackAsPlainJavaTypes() throws Exception {
        assertEquals(UNTYPED, send("/contract/untyped",
                "{\"i\":1,\"l\":3000000000,\"d\":1.5,\"big\":12345678901234567890123,"
                        + "\"s\":\"x\",\"n\":null,\"a\":[1],\"o\":{\"k\":true}}"));
    }

    /* ------------------------------------------------------------------ */
    /* request errors                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void malformedJsonIsA400WithTheValidationErrorShape() throws Exception {
        mvc.perform(post("/contract/bind").contentType(MediaType.APPLICATION_JSON).content("{\"eventCrfId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void aWrongTypeIsA400() throws Exception {
        mvc.perform(post("/contract/bind").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventCrfId\":[1,2]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/contract/bind").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventCrfId\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnreadableBodyGetsTheSameMessageWhateverTheParserSaid() throws Exception {
        String truncated = badRequestBody("{\"eventCrfId\":");
        String wrongType = badRequestBody("{\"eventCrfId\":[1,2]}");
        String notJson = badRequestBody("<xml/>");
        String expected = "{\"message\":\"" + ApiExceptionHandler.UNREADABLE_BODY_MESSAGE + "\",\"errors\":[]}";
        assertEquals(expected, truncated);
        assertEquals(expected, wrongType);
        assertEquals(expected, notJson);
        assertFalse(truncated.contains("com.fasterxml") || truncated.contains("tools.jackson"), truncated);
    }

    private String badRequestBody(String json) throws Exception {
        return mvc.perform(post("/contract/bind").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /* ------------------------------------------------------------------ */
    /* expectations recorded on Jackson 2                                  */
    /* ------------------------------------------------------------------ */

    private static final String DATES_MAP =
            "{\"date\":1767323045678,\"timestamp\":1767323045678,\"instant\":1767323045.678000000,"
            + "\"localDate\":[2026,1,2],\"localDateTime\":[2026,1,2,3,4,5],"
            + "\"offsetDateTime\":1767323045.678000000}";
    private static final String DATES_RECORD =
            "{\"date\":1767323045678,\"timestamp\":1767323045678,\"instant\":1767323045.678000000,"
            + "\"localDate\":[2026,1,2],\"localDateTime\":[2026,1,2,3,4,5],"
            + "\"offsetDateTime\":1767323045.678000000}";
    private static final String NUMBERS =
            "{\"int\":7,\"long\":9007199254740993,\"double\":1.0E10,\"small\":1.0E-4,\"whole\":100.0,"
            + "\"sum\":0.30000000000000004,\"float\":1.1,\"nan\":\"NaN\",\"inf\":\"Infinity\","
            + "\"bigDec\":1E+3,\"bigDecScale\":2.50,\"bigInt\":123456789012345678901234567890,"
            + "\"bool\":true,\"char\":\"c\",\"doubles\":[1.5,2.0]}";
    /** Non-BMP characters leave Jackson 2 as an escaped surrogate pair; U+2028/2029 stay raw. */
    private static final String TEXT =
            "{\"umlaut\":\"Müller ß €\",\"markup\":\"<b>\\\"q\\\" & 'a'</b>/\","
            + "\"controls\":\"tab\\there\\nnew\\u0001\",\"separators\":\"a b c\","
            + "\"astral\":\"\\uD83D\\uDE00\"}";
    private static final String CATALOG =
            "{\"code\":\"OPHTH_X\",\"labelDe\":\"Zeile\",\"labelEn\":\"Line\",\"hintDe\":null,"
            + "\"hintEn\":\"hint\",\"bilateral\":true,\"dataType\":\"REAL\",\"widget\":\"number\","
            + "\"unit\":\"mm\",\"minValue\":0.5,\"maxValue\":1000,\"stepValue\":0.10,"
            + "\"placeholderText\":null,\"conditionalOnCode\":null,\"conditionalShowWhenValue\":null,"
            + "\"responseOptions\":[{\"value\":\"1\",\"label\":\"Ja\"}],\"modalityCode\":null,"
            + "\"oidPrefix\":\"OPHTH\",\"ordinal\":3}";
    private static final String POJO = "{\"zebra\":\"z\",\"apple\":\"a\",\"mango\":\"m\"}";
    private static final String COERCE_OK =
            "{\"n\":12,\"boxed\":null,\"l\":7,\"d\":1.5,\"b\":true,\"s\":\"42\",\"dec\":2.50}";
    private static final String COERCE_EMPTY =
            "{\"n\":0,\"boxed\":null,\"l\":0,\"d\":0.0,\"b\":false,\"s\":\"\",\"dec\":null}";
    private static final String UNTYPED =
            "{\"i\":\"java.lang.Integer\",\"l\":\"java.lang.Long\",\"d\":\"java.lang.Double\","
            + "\"big\":\"java.math.BigInteger\",\"s\":\"java.lang.String\",\"n\":null,"
            + "\"a\":\"java.util.ArrayList\",\"o\":\"java.util.LinkedHashMap\"}";

    /* ------------------------------------------------------------------ */
    /* probe                                                               */
    /* ------------------------------------------------------------------ */

    record Dates(Date date, java.sql.Timestamp timestamp, Instant instant, LocalDate localDate,
                 LocalDateTime localDateTime, OffsetDateTime offsetDateTime) {}

    record Coerce(int n, Integer boxed, long l, double d, boolean b, String s, BigDecimal dec) {}

    public static class Pojo {
        private final String zebra = "z";
        private final String apple = "a";
        private final String mango = "m";

        public String getZebra() { return zebra; }
        public String getApple() { return apple; }
        public String getMango() { return mango; }
    }

    @RestController
    static class Probe {
        private static final long T = 1_767_323_045_678L; // 2026-01-02T03:04:05.678Z
        private static final Instant I = Instant.ofEpochMilli(T);

        @GetMapping("/contract/dates-map")
        Map<String, Object> datesMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", new Date(T));
            m.put("timestamp", new java.sql.Timestamp(T));
            m.put("instant", I);
            m.put("localDate", LocalDate.of(2026, 1, 2));
            m.put("localDateTime", LocalDateTime.of(2026, 1, 2, 3, 4, 5));
            m.put("offsetDateTime", I.atOffset(ZoneOffset.UTC));
            return m;
        }

        @GetMapping("/contract/dates-record")
        Dates datesRecord() {
            return new Dates(new Date(T), new java.sql.Timestamp(T), I, LocalDate.of(2026, 1, 2),
                    LocalDateTime.of(2026, 1, 2, 3, 4, 5), I.atOffset(ZoneOffset.UTC));
        }

        @GetMapping("/contract/numbers")
        Map<String, Object> numbers() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("int", 7);
            m.put("long", 9_007_199_254_740_993L);
            m.put("double", 1.0E10);
            m.put("small", 1.0E-4);
            m.put("whole", 100.0);
            m.put("sum", 0.1 + 0.2);
            m.put("float", 1.1f);
            m.put("nan", Double.NaN);
            m.put("inf", Double.POSITIVE_INFINITY);
            m.put("bigDec", new BigDecimal("1E+3"));
            m.put("bigDecScale", new BigDecimal("2.50"));
            m.put("bigInt", new java.math.BigInteger("123456789012345678901234567890"));
            m.put("bool", true);
            m.put("char", 'c');
            m.put("doubles", new double[] {1.5, 2.0});
            return m;
        }

        @GetMapping("/contract/text")
        Map<String, Object> text() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("umlaut", "Müller ß €");
            m.put("markup", "<b>\"q\" & 'a'</b>/");
            m.put("controls", "tab\there\nnew\u0001");
            m.put("separators", "a b c");
            m.put("astral", "😀");
            return m;
        }

        @GetMapping("/contract/order-map")
        Map<String, Object> orderMap() {
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("b", 2);
            inner.put("a", 1);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("zeta", 1);
            m.put("alpha", null);
            m.put("mid", inner);
            m.put("list", new ArrayList<>(List.of(3, 1, 2)));
            return m;
        }

        @GetMapping("/contract/catalog")
        OphthFieldCatalogDto catalog() {
            return new OphthFieldCatalogDto("OPHTH_X", "Zeile", "Line", null, "hint", true, "REAL", "number",
                    "mm", new BigDecimal("0.5"), new BigDecimal("1000"), new BigDecimal("0.10"), null, null,
                    null, List.of(new OphthFieldCatalogDto.ResponseOption("1", "Ja")), null, "OPHTH", 3);
        }

        @GetMapping("/contract/pojo")
        Pojo pojo() {
            return new Pojo();
        }

        @GetMapping("/contract/non-null")
        StudyUserDto nonNull() {
            return new StudyUserDto("7", "u", null, null, null, null, null, null, null, true, false,
                    null, null, null, null, null, null, null, null, null);
        }

        @GetMapping("/contract/non-null-audit")
        AuditEventDto nonNullAudit() {
            return new AuditEventDto("1", "2026-01-02T03:04:05Z", null, null, null, null, null, null,
                    null, null, null, null);
        }

        @PostMapping("/contract/migrate")
        Map<String, Object> migrate(@RequestBody MigrateVersionRequest r) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dryRun", r.dryRun());
            m.put("sedOids", r.sedOids());
            return m;
        }

        @PostMapping("/contract/bind")
        Map<String, Object> bind(@RequestBody RetinalResultsApiController.BindRequest r) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventCrfId", r.eventCrfId());
            return m;
        }

        @PostMapping("/contract/bulk-bind")
        Map<String, Object> bulkBind(@RequestBody RetinalResultsApiController.BulkBindRequest r) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobIds", r.jobIds());
            m.put("eventCrfId", r.eventCrfId());
            return m;
        }

        @PostMapping("/contract/coerce")
        Coerce coerce(@RequestBody Coerce c) {
            return c;
        }

        @PostMapping("/contract/untyped")
        Map<String, Object> untyped(@RequestBody Map<String, Object> in) {
            Map<String, Object> out = new LinkedHashMap<>();
            in.forEach((k, v) -> out.put(k, v == null ? null : v.getClass().getName()));
            return out;
        }
    }
}
