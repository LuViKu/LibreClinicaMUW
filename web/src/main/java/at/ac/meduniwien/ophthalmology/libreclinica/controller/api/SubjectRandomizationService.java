/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyGroupBean;

/**
 * 2026-07-02 — Subject-randomization picker for the enrollment flow.
 *
 * <p>Backs the {@code POST /studies/{oid}/group-classes/{id}/randomize}
 * endpoint. Stateless — every call generates a fresh seed +
 * cryptographic pick; the caller is responsible for round-tripping the
 * seed + resulting group id through the enrollment POST so
 * {@link SubjectGroupAssignmentService#reconcile} lands the assignment
 * with the correct {@code randomization_source} / {@code _seed}
 * columns.
 *
 * <h2>v1 pickers</h2>
 *
 * <ul>
 *   <li>{@link #pickUniform(List)} — legacy 1:1 pick; equal probability
 *     across the input groups.</li>
 *   <li>{@link #pickWeighted(List)} — reads
 *     {@link StudyGroupBean#getAllocationWeight()} (v2a) and does a
 *     weighted uniform pick. Falls through to uniform when every
 *     weight is 1 or when a weight is absent.</li>
 * </ul>
 *
 * <h2>Seed handling</h2>
 *
 * <p>Each pick generates 32 bytes from {@link SecureRandom}
 * (getInstanceStrong when available; falls through to the default
 * SecureRandom otherwise — under Testcontainers the strong-entropy
 * source is slow, so tests that don't need audit-grade seeds fall
 * back gracefully). The seed is hex-encoded to 64 characters and
 * returned alongside the picked group in the {@link Result} record.
 *
 * <p>Seed → group-index mapping uses the first 8 bytes of the seed
 * interpreted as an unsigned 64-bit integer modulo the cumulative
 * weight total. This makes the pick deterministic given the seed —
 * useful for v2d's sealed-envelope reveal path (same seed → same
 * group without re-rolling).
 */
public class SubjectRandomizationService {

    /** Result of a single pick — the picked group + audit-grade metadata. */
    public record Result(
            int groupId,
            String groupName,
            String seedHex,
            String source) { }

    /** Backing RNG. Package-private setter for the IT to swap in a deterministic seed. */
    private final SecureRandom rng;

    public SubjectRandomizationService() {
        SecureRandom r;
        try {
            r = SecureRandom.getInstanceStrong();
        } catch (Exception e) {
            r = new SecureRandom();
        }
        this.rng = r;
    }

    /** Constructor for the IT — pass a pre-seeded RNG for deterministic distributions. */
    SubjectRandomizationService(SecureRandom rng) {
        this.rng = rng;
    }

    /**
     * Pure uniform pick — each group has equal probability regardless
     * of its {@code allocation_weight}. Used when the caller doesn't
     * want the weighted-uniform behaviour (v1 default path).
     *
     * @throws IllegalArgumentException when {@code groups} is empty.
     */
    public Result pickUniform(List<StudyGroupBean> groups) {
        requireNonEmpty(groups);
        byte[] seed = freshSeed();
        int idx = indexFromSeed(seed, groups.size());
        StudyGroupBean picked = groups.get(idx);
        return new Result(picked.getId(), picked.getName(), HexFormat.of().formatHex(seed),
                "RANDOMIZED_UNIFORM");
    }

    /**
     * Weighted-uniform pick (v2a). Reads {@link StudyGroupBean#getAllocationWeight()}
     * from each group; a weight of {@code w} means the group is picked
     * with probability {@code w / sum(all weights)}. Weights ≤ 0 are
     * treated as 1 (defensive — a zero-weight group would silently
     * become uneligible without any operator signal, which is bad UX).
     *
     * <p>When every weight resolves to 1 the outcome is
     * indistinguishable from {@link #pickUniform} — the caller may
     * always use {@code pickWeighted} without worrying about the
     * simple case.
     */
    public Result pickWeighted(List<StudyGroupBean> groups) {
        requireNonEmpty(groups);
        int[] weights = new int[groups.size()];
        long total = 0;
        for (int i = 0; i < groups.size(); i++) {
            int w = groups.get(i).getAllocationWeight();
            if (w <= 0) w = 1;
            weights[i] = w;
            total += w;
        }
        byte[] seed = freshSeed();
        // 2026-07-02 — MUST use Long.remainderUnsigned. The signed `%`
        // returns a negative value whenever the seed's high bit is set
        // (probability 0.5 per call), which folded every "negative"
        // draw onto group index 0 and produced a ~75/25 bias in the IT
        // distribution test. `remainderUnsigned` interprets the long as
        // unsigned so the modulo lands in [0, total) uniformly.
        long rolled = Long.remainderUnsigned(unsignedLongFromSeed(seed), total);
        int idx = 0;
        long acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (rolled < acc) { idx = i; break; }
        }
        StudyGroupBean picked = groups.get(idx);
        // v2a doesn't emit RANDOMIZED_WEIGHTED as its own source when
        // the outcome is identical to uniform — trial-master audit
        // clarity favours "we did a weighted pick where all weights =
        // 1" being marked RANDOMIZED_UNIFORM.
        boolean anyWeightNonOne = false;
        for (int w : weights) if (w != 1) { anyWeightNonOne = true; break; }
        String source = anyWeightNonOne ? "RANDOMIZED_WEIGHTED" : "RANDOMIZED_UNIFORM";
        return new Result(picked.getId(), picked.getName(), HexFormat.of().formatHex(seed),
                source);
    }

    /** Build the {@code randomization_meta} JSON envelope for a weighted pick. */
    public static String weightedMetaJson(List<StudyGroupBean> groups) {
        StringBuilder ratio = new StringBuilder("[");
        List<Integer> gathered = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            int w = groups.get(i).getAllocationWeight();
            if (w <= 0) w = 1;
            gathered.add(w);
        }
        for (int i = 0; i < gathered.size(); i++) {
            if (i > 0) ratio.append(',');
            ratio.append(gathered.get(i));
        }
        ratio.append(']');
        return "{\"ratio\":" + ratio + "}";
    }

    // ─── internals ──────────────────────────────────────────────

    private byte[] freshSeed() {
        byte[] out = new byte[32];
        rng.nextBytes(out);
        return out;
    }

    /**
     * Deterministic seed → index-in-range. Uses the first 8 bytes of
     * the seed interpreted as an unsigned 64-bit integer; folds it via
     * modulo. Modulo bias for {@code n <= 100} is negligible — well
     * inside the CI a χ² test would tolerate.
     */
    static int indexFromSeed(byte[] seed, int n) {
        return (int) Long.remainderUnsigned(unsignedLongFromSeed(seed), n);
    }

    private static long unsignedLongFromSeed(byte[] seed) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (seed[i] & 0xFFL);
        }
        return v;
    }

    private static void requireNonEmpty(List<StudyGroupBean> groups) {
        if (groups == null || groups.isEmpty()) {
            throw new IllegalArgumentException("Group list must be non-empty");
        }
    }
}
