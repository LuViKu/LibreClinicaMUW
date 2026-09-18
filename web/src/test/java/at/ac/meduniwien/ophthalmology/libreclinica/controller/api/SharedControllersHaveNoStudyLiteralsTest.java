/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * P3.9 — one study's vocabulary must not spread into code every study runs.
 *
 * <p>Shared controllers used to name a particular trial's CRF out loud:
 * {@code F_NAMD_VISIT} is the form the decision panel writes to,
 * {@code I_NAMD_OD_IRF_MM3} is where a metric lands, {@code AI_SHOWN} is what
 * one trial calls a randomisation group. Every such literal is a study that
 * cannot be onboarded without editing code the others share — which is the
 * concrete reason a third study was expensive, and the thing P3.4 and P3.5
 * built the catalogue and the bindings to end.
 *
 * <p><strong>This is a ratchet, not a clean sweep.</strong> The literals that
 * remain are deliberate fallbacks: {@code StudyBindings.oidFor} takes the old
 * OID as its default so a study that has configured nothing behaves exactly as
 * before, which is what let P3.5 ship without a data migration. Each file
 * still holding one is listed below with the reason. The test fails when a
 * file <em>not</em> on that list acquires one.
 *
 * <p>So the list may shrink and must never grow. When the last study has its
 * bindings configured, the fallbacks go and the entries go with them; until
 * then this stops the problem getting worse while it is being fixed.
 */
class SharedControllersHaveNoStudyLiteralsTest {

    /**
     * Names belonging to one study, which shared code must not contain.
     *
     * <p>{@code S_HAE} / {@code S_NAMD} are study OIDs; the rest are CRF and
     * group names those studies chose.
     */
    private static final Pattern STUDY_LITERAL = Pattern.compile(
            "F_NAMD|I_NAMD_|NAMD_O[DS]_|AI_SHOWN|AI_HIDDEN|I_HEALT_|\"S_HAE\"|\"S_NAMD\"");

    /**
     * Where a study literal is still allowed, and why.
     *
     * <p>Every entry is a fallback kept so behaviour did not change when the
     * configuration replaced it. Removing one means deleting the fallback,
     * which is safe only once every deployment's studies have their rows.
     */
    private static final Map<String, String> ALLOWED = new LinkedHashMap<>();

    static {
        ALLOWED.put("AiArmPolicy.java",
                "The two group names double as the canonical tokens every masking call "
                        + "site compares against; a study's own names are translated onto them.");
        ALLOWED.put("StudyBindings.java",
                "Javadoc examples naming the roles' original OIDs.");
        ALLOWED.put("RetinalResultItemDataPopulator.java",
                "P3.5 fallbacks — the OID each metric used before study_item_binding existed.");
        ALLOWED.put("EventCrfsApiController.java",
                "P3.5 pending: the nAMD decision audit still names F_NAMD_VISIT directly.");
        ALLOWED.put("RetinalResultsApiController.java",
                "P3.5/P3.6 pending: the flags endpoint still resolves F_NAMD_VISIT and the "
                        + "four NAMD_O?_ flag items directly. P3.6 splits this controller.");
        ALLOWED.put("SubjectsApiController.java",
                "Comment prose describing the AI cohort; the SQL reads AiArmPolicy.");
        ALLOWED.put("PerformedItemAutoTicker.java",
                "Javadoc naming I_HEALT_OPTOMED_PERFORMED as the worked example.");
        ALLOWED.put("AuditTypeIds.java",
                "Javadoc prose describing what an audit type records.");
    }

    /** Where shared code lives. Study modules are the SPA's, not Java's. */
    private static final List<String> ROOTS = List.of(
            "src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/controller/api",
            "src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/service");

    @Test
    void noNewStudyLiteralAppearsInSharedCode() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sharedJavaFiles()) {
            String name = file.getFileName().toString();
            if (ALLOWED.containsKey(name)) continue;
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (STUDY_LITERAL.matcher(body).find()) {
                offenders.add(name + "  (" + file + ")");
            }
        }
        if (!offenders.isEmpty()) {
            fail("One study's CRF or group names have appeared in code every study runs:\n  "
                    + String.join("\n  ", offenders)
                    + "\n\nAsk the study what it means instead: StudyBindings.oidFor(studyId, "
                    + "role, fallback) for an item, AiArmPolicy for a randomisation group, and "
                    + "the imaging_modality catalogue for a device's checklist box. If the "
                    + "literal really must stay as a fallback, add it to ALLOWED with the "
                    + "reason — that list may shrink and must never grow.");
        }
    }

    /**
     * The allowlist itself has to stay honest: an entry for a file that no
     * longer has a literal is a claim nobody checked, and it would quietly
     * re-permit one later.
     */
    @Test
    void theAllowlistHasNoStaleEntries() throws IOException {
        Set<String> withLiterals = new java.util.LinkedHashSet<>();
        for (Path file : sharedJavaFiles()) {
            if (STUDY_LITERAL.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                withLiterals.add(file.getFileName().toString());
            }
        }
        List<String> stale = ALLOWED.keySet().stream()
                .filter(name -> !withLiterals.contains(name))
                .toList();
        assertTrue(stale.isEmpty(),
                "These files no longer contain a study literal — remove them from ALLOWED "
                        + "so the list keeps meaning what it says: " + stale);
    }

    /* ------------------------------------------------------------------ */

    /** Both modules' shared trees; tests and resources are not shared code. */
    private static List<Path> sharedJavaFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String module : List.of("web", "core")) {
            for (String root : ROOTS) {
                Path dir = moduleRoot(module).resolve(root);
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
                }
            }
        }
        assertTrue(out.size() > 50,
                "expected to scan the shared controller and service trees, found " + out.size()
                        + " files — has the layout moved?");
        return out;
    }

    /**
     * Surefire runs with the module as the working directory, so a sibling
     * module is one level up. Tolerates being run from the reactor root.
     */
    private static Path moduleRoot(String module) {
        Path here = Path.of("").toAbsolutePath();
        if (here.getFileName() != null && here.getFileName().toString().equals(module)) {
            return here;
        }
        Path sibling = here.resolveSibling(module);
        if (Files.isDirectory(sibling)) return sibling;
        return here.resolve(module);
    }
}
