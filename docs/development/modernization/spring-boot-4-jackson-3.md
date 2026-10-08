# Spring Boot 4, stage 2: Jackson 2 to Jackson 3

Branch `spike/muw-spring-boot-4`. Stage 1 moved Spring to 7.0.9, Security to
7.1.1, Hibernate to 7 and Tomcat to 11, with the application still on Jackson
2.21.5. Stage 2 moves the application to Jackson 3 (`tools.jackson`, 3.1.5, the
line Boot 4.1.1 manages). The project owner chose Jackson 3 over staying on the
deprecated Jackson 2 support Spring 7 still ships.

**The rule for this stage:** what the SPA receives, what it may send, and every
byte of JSON the application stores stay exactly what Jackson 2 produced and
accepted. There is one deliberate exception, the job-admin dates (see below).

## What changed

| Area | Before | After |
|------|--------|-------|
| HTTP converter | `MappingJackson2HttpMessageConverter` on Spring's own mapper | `JacksonJsonHttpMessageConverter` on the shared mapper, same list `[ByteArray, Marshalling, Jackson]`, same `jacksonMessageConverter` bean name and `@Qualifier` |
| Mappers | ~30 `new ObjectMapper()` sites | `Json.mapper()` and `Json.strict()`, built once in `core.util.Json` |
| Mapper bean | none | `applicationJsonMapper` in `WebMvcConfig`, returning the same `Json.mapper()` instance |
| `RestTemplate` (retinal sidecar clients) | default converters, JSON converter picked by classpath detection | `Json.restTemplate(requestFactory)`: defaults plus a `JacksonJsonHttpMessageConverter` on the shared mapper, stated explicitly |
| Terminology streaming | `com.fasterxml...JsonFactory` | `tools.jackson.core.json.JsonFactory.builderWithJackson2Defaults()` |
| `JsonNode` reads | `asText()`, `fields()`, `canConvertToInt()` | `Json.text()`, `properties()`, `asString(null)` / `asInt(0)` ... (see below) |
| Exceptions | `catch (IOException)` around Jackson calls | `catch (JacksonException)` (unchecked) |
| Annotations | `com.fasterxml.jackson.annotation` | unchanged: Jackson 3 uses the same package and the 2.21 annotations artifact |
| Dependencies | Jackson 2 BOM import and three version pins in the root pom; direct `jackson-core/databind` in web | none of those; `tools.jackson.core:jackson-databind` and `-core` declared in core and web, versions from Boot's BOM |

`dependency:analyze` is clean for web (core skips it by configuration, as
before). Jackson 2 stays on the classpath only through
`logstash-logback-encoder` 7.4 and swagger-core (springdoc), plus the shared
annotations.

## The mappers (`core.util.Json`)

Both are built from `JsonFactory.builderWithJackson2Defaults()` and
`JsonMapper.builder(...).configureForJackson2()` (Jackson's own switch back to
its 2.x defaults), then `DEFAULT_VIEW_INCLUSION` off as Spring's converter had
it. `JsonTest` (core) asserts every row of the table below on both mappers, so
a library bump that moves a default fails a test rather than a Thursday export.

* `Json.mapper()`: used by the `pages` converter, the legacy JSON servlets,
  `RestTemplate`, and every writer or reader of stored JSON except the two
  below. Unknown properties on input are **ignored**, as Spring's converter
  always did.
* `Json.strict()`: identical except unknown properties **fail** the read. Used
  only by the two readers that bind a stored `crf_item_terminology.fill_map` to
  records (`CrfsApiController`, `EventCrfsApiController`): a plain Jackson 2
  `ObjectMapper` was strict, and a malformed map must fall back to "system
  only" rather than half-load. The behaviour is pinned by
  `StoredJsonGoldenTest.fillMapReadIsStrictAboutUnknownPropertiesAndRecoversEmpty`.

### Feature table

Values were measured, not remembered: Jackson 3 defaults from
`JsonMapper.builder().build()` on the 3.1.5 jars, Jackson 2 from a mapper on
2.21.5 configured the way `Jackson2ObjectMapperBuilder` did (the Spring
converter's mapper). "Effect" names where the application can see it.

| Feature | Jackson 2 | Jackson 3 default | Set to | Effect |
|---------|-----------|-------------------|--------|--------|
| `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` | true | **false** | true | `java.util.Date` as epoch millis, `Instant` as decimal seconds, `LocalDate` as `[y,m,d]` |
| `DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS` | true | **false** | true | durations as numbers |
| `DateTimeFeature.ONE_BASED_MONTHS` | n/a (Calendar months 0-based) | true | false | only `Calendar`-typed values |
| `DateTimeFeature.WRITE_UTC_AS_OFFSET` | n/a | false | true | textual time forms only; irrelevant with timestamps on |
| `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY` | false | **true** | false | POJO property order is declaration order (records were unaffected either way) |
| `MapperFeature.FIX_FIELD_NAME_UPPER_CASE_PREFIX` | false | true | false | property names derived from `getURL`-style names |
| `MapperFeature.DETECT_PARAMETER_NAMES` | false (module not on the classpath) | true | false | constructor-parameter binding of plain classes |
| `MapperFeature.USE_GETTERS_AS_SETTERS` | true | **false** | true | collection getters used as setters on input |
| `MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS` | true | **false** | true | final fields written on input |
| `SerializationFeature.FAIL_ON_EMPTY_BEANS` | true | **false** | true | a bean with no properties fails (500) instead of writing `{}` |
| `EnumFeature.WRITE_ENUMS_USING_TO_STRING` | false | **true** | false | enums go out as `name()`, not an overridden `toString()` |
| `EnumFeature.READ_ENUMS_USING_TO_STRING` | false | **true** | false | same on input |
| `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` | false | **true** | false | `null`/missing `int`, `boolean` become 0/false, never a 400 |
| `DeserializationFeature.FAIL_ON_TRAILING_TOKENS` | false | **true** | false | junk after the JSON value is ignored |
| `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` | false (Spring) / true (plain) | false | false (`mapper()`) / true (`strict()`) | see above |
| `JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES` | true | false | true | `readTree` of a big decimal |
| `JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8` | false | **true** | false | a character outside the BMP is written as an escaped surrogate pair in byte output (`manifest.json`), not raw UTF-8: same JSON, different bytes |
| `JsonWriteFeature.ESCAPE_FORWARD_SLASHES` | false | false | false | unchanged, pinned by the builder |
| `StreamReadFeature.USE_FAST_DOUBLE_PARSER` / `USE_FAST_BIG_NUMBER_PARSER` | false | **true** | false | the number parsers Jackson 2 used; costs nothing here |
| `StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` | false | false | false | error locations carry no body snippet |

Everything else that `JsonTest` asserts is at its default on both lines (for
example `ACCEPT_FLOAT_AS_INT`, `ALLOW_COERCION_OF_SCALARS`, `WRITE_NAN_AS_STRINGS`,
`USE_BIG_DECIMAL_FOR_FLOATS`, `INDENT_OUTPUT`, `ORDER_MAP_ENTRIES_BY_KEYS`).

Two differences were left on Jackson 3's side on purpose:

* Jackson 3 supports `Optional` and `java.time` out of the box. Jackson 2 here
  had no jdk8 module (an `Optional` in a response failed with a 500), and its
  jsr310 module was only there transitively. Nothing on the wire uses either
  today, so nothing observable changes; with `WRITE_DATES_AS_TIMESTAMPS` on,
  `java.time` values follow the numeric forms in the table.
* `FAIL_ON_EMPTY_BEANS` is kept on, so an empty bean still fails loudly.

## `JsonNode` and exceptions

Jackson 3's node accessors are strict: `asInt()`, `asText()`, `intValue()` throw
`JsonNodeException` on a node that is not the expected kind where Jackson 2
quietly returned 0 or `""`. The clients that read sidecar and gateway JSON
(Remidio, DICOM describe, retinal cluster health, CRT geometry, upload
heartbeat) must keep tolerating odd values, so:

* `asText()` became `Json.text(node)`, which reproduces Jackson 2 exactly
  (`"null"` for a JSON null, `""` for missing, objects and arrays).
* `asText(null)` became `asString(null)` (verified equal except for containers,
  where both versions end in the same "no token" branch).
* `asInt()/asLong()/asDouble()/asBoolean()` became the defaulting overloads
  (`asInt(0)` ...), which do not throw.
* `UploaderHeartbeat.clampInterval` used `canConvertToInt()`, which in Jackson 3
  rejects a fraction; a local `fitsInt` keeps `1.5` accepted as `1`.
* `fields()` became `properties()`.

Jackson 3 exceptions are unchecked `JacksonException`. Every former
`catch (IOException)` around a Jackson call now catches `JacksonException`
explicitly (Remidio gateway and dashboard, cluster health, CRT geometry,
`RetinalResultItemDataPopulator`, `UploaderHeartbeatApiController`, where bad
JSON is still a 400, and `DicomDescribeClient`'s request building). Where the
old clause was `IOException | RuntimeException` it is now `RuntimeException`,
which includes `JacksonException`. `readTree("")` still returns a missing node
rather than throwing (`JsonTest`).

### Unreadable request bodies

`HttpMessageNotReadableException` used to return its own message, which is the
parser's ("JSON parse error: Unexpected end-of-input ...", or the controller
signature for a missing body) and therefore changes with the library. It now
has its own handler in `ApiExceptionHandler` returning one stable message in
the same `ValidationErrorBody` shape (`message` plus `errors: []`). The SPA only
shows `message`; the cause is in the debug log.

## The one deliberate wire change

`JobsAdminApiController` put Quartz's `java.util.Date` straight into the
response, so `previousFireTime`, `nextFireTime` and `finalFireTime` were epoch
milliseconds, while `AdminJobsView.vue` types them as ISO strings and renders
them with `new Date(string)`. They are now formatted in the controller as
ISO-8601 UTC (`Instant.toString()`, for example `2026-10-07T10:15:30Z`), so the
form no longer depends on mapper configuration. `JobsAdminApiControllerTest.
fireTimesAreIsoUtcStringsNotEpochMillis` covers it, including a null
`finalFireTime`. Nothing else on the wire differs.

## Tests

Before this stage no test exercised the converters production uses: about 100 test
classes built MockMvc with `standaloneSetup`, which registers MockMvc's own
defaults.

* `WebMvcConfig.apiMessageConverters(...)` is the dispatcher's converter list as
  a static method; the adapter bean and the tests both use it.
* `testsupport/ProductionMvc.standalone(...)` builds MockMvc on that list, and
  every standalone test now uses it (`AbstractApiControllerTest.mockMvcFor`
  included). `ProductionMvc.read(type, json)` reads a body the way the
  dispatcher does. This is what made the 13 failing `CrfsApiControllerTest`
  cases pass: the cause was MockMvc's default converter, not the tests.
* `JsonWireContractTest` pins the wire: content type, dates, numbers (NaN,
  big decimals, double forms), text, record and map order, POJO declaration
  order, `@JsonInclude(NON_NULL)` DTOs, `BigDecimal` as a number, null and
  missing primitives (`MigrateVersionRequest.dryRun`, the retinal
  `BindRequest`/`BulkBindRequest.eventCrfId`: both become false/0, as before),
  unknown properties, trailing tokens, scalar coercion, untyped bodies, and the
  400 shape. Its expectations were **recorded on Jackson 2** (commit `ead84771d`
  is the pre-migration state) and not edited afterwards.
* `StoredJsonGoldenTest` and `BundleManifestGoldenTest` compare bytes with
  fixtures in `web/src/test/resources/golden/json`: the CRF item terminology
  `fill_map`, the dataset filter column, the retinal `output_payload`, and the
  pretty-printed `manifest.json` (whitespace included; no intentional
  difference). The fixtures were written by the Jackson 2 code and are
  identical under Jackson 3. They include non-BMP characters, `/` and the
  double forms that differ between parsers and writers. `.gitattributes` marks
  them `-text` and the root pom copies `golden/**` unfiltered, so neither git
  nor Maven rewrites them. Regenerate only on purpose, with
  `-Dgolden.write=true`, and review the diff.
* `JsonTest` (core) asserts the feature table.
* Mutation check: with the Jackson 3 defaults instead of `configureForJackson2()`
  the contract test fails on property order, dates, null primitives, scalar
  coercion, text escaping and trailing tokens (six tests).

The manifest bytes use the platform line separator for the pretty printer; the
gate runs on Linux, as `CLAUDE.md` says.

### Two things found on the way

* `Map.of(...)` iterates in a per-JVM-salted order, so the `omitted` entries of
  `manifest.json` were not byte-stable between runs. `BundleExportWriter` now
  writes them from ordered maps (`ref`, then `reason`).
* **The `pages` dispatcher's converter list has no String and no Resource
  converter.** It is `[ByteArray, Marshalling, Jackson]`, unchanged by this
  stage. Running the database ITs on that list (they used MockMvc's defaults
  before) showed three endpoints that cannot work through it:
  * `CrfsApiController.uploadVersion` takes `versionName`, `versionDescription`
    and `revisionNotes` as `String` `@RequestPart`s. A browser sends a multipart
    text field with no content type (or `text/plain`), which no converter reads:
    `HttpMediaTypeNotSupportedException`.
  * `StudyMetadataApiController.metadata` returns a `String` with content type
    `application/xml`: `HttpMessageNotWritableException` ("No converter for ... with preset Content-Type").
  * `EventCrfsApiController.downloadItemFile` returns a `FileSystemResource`:
    the same.

  Each answered 500 (or 415) with this list. Other controllers hit the same wall
  earlier and stream to the response or return `byte[]` instead (see the comments in
  `RetinalJobArtifactsApiController`, `ImageIngestApiController`,
  `SubjectExportApiController`).

  **Confirmed on the production line.** lc-develop (Boot 3.5, Jackson 2) has the same
  list in `WebMvcConfig`, and the same three controller signatures. A probe built on
  that list, with Spring 6.2's converters, answers 415 for the multipart text field
  (with or without `text/plain`) and 500 for the `application/xml` String and the
  Resource (`HttpMessageNotWritableException: No converter for ... with preset
  Content-Type`). The SPA calls all three: `crfLibrary.ts` `uploadVersion` (CRF
  version upload form), `api/studyMetadata.ts` (study metadata download) and
  `crfEntry.ts` (file item download). Not exercised against a running instance by
  this stage's author, so the user impact is derived from the converter list and the
  SPA's calls, not observed.

  **Fixed, in a separate commit** (so it can be cherry-picked onto lc-develop),
  without touching the list: `uploadVersion` reads its three text fields with
  `@RequestParam` (the servlet container exposes a form field without a filename as
  a parameter, whatever its content type); `metadata` writes the document as UTF-8
  `byte[]`; `downloadItemFile` reads the file into a `byte[]`. A global
  `StringHttpMessageConverter` was rejected because it changes how every
  `@ResponseBody String` is written. The regression tests (`PagesDispatcherMvc`,
  built from the `requestMappingHandlerAdapter` bean method, not MockMvc's
  defaults) are `CrfsApiControllerUploadFormDatabaseIT`,
  `StudyMetadataDownloadDatabaseIT` and `ItemFileDownloadDatabaseIT`. The gap
  fillers (`ProductionMvc.standaloneWithGapFillers`) are gone.
  `ConverterListGapTest` stays: it still records, with the list as it is, what a
  new endpoint must not do.

## OpenAPI (springdoc 3)

The `spa-api` document was generated from the running application before
(stage 1, Jackson 2 converter) and after (Jackson 3) on Tomcat 11: **byte-for-byte
identical**, 192,384 bytes, OpenAPI 3.1.0, 261 paths, 226 schemas. springdoc
introspects classes with swagger-core's own (Jackson 2) mapper, whose defaults
already matched, so no pin was needed. The CI drift guard on `api.ts` is
unaffected.

## Left for stage 3

* The authenticated smoke (SPA login, `/pages/api/v1/me`, the admin jobs list)
  was not run on the live stack; see the report. Everything below the login
  was covered by the contract and golden tests, the web unit suite and the
  database ITs, and the unauthenticated live checks (startup, login page, the
  retired-page JSON from `LegacyServletTelemetryFilter`, the OpenAPI document).
* ~~Decide the `String` `@RequestPart` gap above.~~ Done: the three endpoints were fixed (see above).
* `springdoc` and `swagger-core` still bring Jackson 2; drop it when springdoc
  does.
* `logstash-logback-encoder` 7.4 needs Jackson 2 at runtime; a Jackson 3 release
  of it (or `logback-json-classic`) removes the last direct dependency.
