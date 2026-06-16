/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RetinalArtifactStorageServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private RemoteRunResult.Artifact artifact(String name, String content) {
        return new RemoteRunResult.Artifact(
                name,
                "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void persistInto_writesAllArtifacts() throws IOException {
        RetinalArtifactStorageService svc = new RetinalArtifactStorageService();
        Path jobDir = tmp.newFolder("job-1").toPath();

        List<RemoteRunResult.Artifact> artifacts = List.of(
                artifact("001-OPL-HFL.csv", "header\n1,2,3\n"),
                artifact("002-BMEIS.csv", "header\n4,5,6\n")
        );

        Path resultDir = svc.persistInto(101L, artifacts, jobDir);
        assertEquals(jobDir, resultDir);

        Path csv1 = jobDir.resolve("001-OPL-HFL.csv");
        Path csv2 = jobDir.resolve("002-BMEIS.csv");
        assertTrue(Files.isRegularFile(csv1));
        assertTrue(Files.isRegularFile(csv2));
        assertArrayEquals("header\n1,2,3\n".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(csv1));
        assertArrayEquals("header\n4,5,6\n".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(csv2));
    }

    @Test
    public void persistInto_stripsDirectoryTraversal() throws IOException {
        RetinalArtifactStorageService svc = new RetinalArtifactStorageService();
        Path jobDir = tmp.newFolder("job-2").toPath();

        List<RemoteRunResult.Artifact> artifacts = List.of(
                artifact("../escape.csv", "boom")
        );

        svc.persistInto(202L, artifacts, jobDir);

        // Must NOT escape jobDir.
        Path escape = jobDir.getParent().resolve("escape.csv");
        assertTrue("must not write outside job dir: " + escape, !Files.exists(escape));
        Path expected = jobDir.resolve("escape.csv");
        assertTrue(Files.isRegularFile(expected));
    }

    @Test
    public void persistInto_emptyListProducesEmptyDir() throws IOException {
        RetinalArtifactStorageService svc = new RetinalArtifactStorageService();
        Path jobDir = tmp.newFolder("job-3").toPath();

        Path resultDir = svc.persistInto(303L, List.of(), jobDir);
        assertEquals(jobDir, resultDir);
        try (var stream = Files.list(jobDir)) {
            assertEquals(0, stream.count());
        }
    }

    @Test
    public void persistInto_overwritesExistingFile() throws IOException {
        RetinalArtifactStorageService svc = new RetinalArtifactStorageService();
        Path jobDir = tmp.newFolder("job-4").toPath();
        Path existing = jobDir.resolve("results.csv");
        Files.writeString(existing, "stale\n");

        svc.persistInto(404L, List.of(artifact("results.csv", "fresh\n")), jobDir);

        assertArrayEquals("fresh\n".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(existing));
    }

    @Test
    public void persist_createsUuidScopedJobDirUnderStore() throws IOException {
        Path storeRoot = tmp.newFolder("store").toPath();
        RetinalArtifactStorageService svc = new RetinalArtifactStorageService() {
            @Override protected String storePath() { return storeRoot.toString(); }
        };

        RemoteRunResult result = new RemoteRunResult(
                "fluid-1.3.0", 6.84, "mm³",
                java.util.Map.of(), 0.9,
                List.of(artifact("a.csv", "x\n")),
                "fluid", "OD");

        Path jobDir = svc.persist(505L, result);
        assertTrue(jobDir.startsWith(storeRoot));
        assertTrue(Files.isRegularFile(jobDir.resolve("a.csv")));
    }
}