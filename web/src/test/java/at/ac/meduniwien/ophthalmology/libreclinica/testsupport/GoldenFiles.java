package at.ac.meduniwien.ophthalmology.libreclinica.testsupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Byte-exact golden files under {@code src/test/resources/golden/}.
 *
 * <p>A golden file pins the bytes a piece of code writes into storage (a JSONB
 * column, a zip entry). Compare <em>bytes</em>, not parsed trees: a library
 * upgrade that reorders properties or changes number or whitespace formatting
 * leaves the parsed tree equal while changing what is stored.
 *
 * <p>To (re)generate after a deliberate change run the test with
 * {@code -Dgolden.write=true}; the file is written next to the sources and the
 * diff is reviewed like any other change.
 */
public final class GoldenFiles {

    private static final Path SOURCE_DIR = Paths.get("src", "test", "resources", "golden");

    private GoldenFiles() {}

    public static void assertMatches(String name, String actual) throws IOException {
        assertMatches(name, actual.getBytes(StandardCharsets.UTF_8));
    }

    public static void assertMatches(String name, byte[] actual) throws IOException {
        if (Boolean.getBoolean("golden.write")) {
            Path target = SOURCE_DIR.resolve(name);
            Files.createDirectories(target.getParent());
            Files.write(target, actual);
            return;
        }
        try (InputStream in = GoldenFiles.class.getResourceAsStream("/golden/" + name)) {
            assertTrue(in != null, "missing golden file " + name + "; generate with -Dgolden.write=true");
            byte[] expected = in.readAllBytes();
            assertArrayEquals(expected, actual,
                    "golden " + name + " differs.\nexpected:\n" + new String(expected, StandardCharsets.UTF_8)
                            + "\nactual:\n" + new String(actual, StandardCharsets.UTF_8));
        }
    }
}
