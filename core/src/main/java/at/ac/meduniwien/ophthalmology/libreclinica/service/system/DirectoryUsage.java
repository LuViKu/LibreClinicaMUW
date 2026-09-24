/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.system;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DR-033 — how much a file store holds, and how full the disk under it is.
 *
 * <p>Two measurements, deliberately separate because they cost very
 * different amounts:
 * <ul>
 *   <li>{@link #measure} walks a directory tree and sums the regular files
 *       in it. That is a metadata read per file — seconds for a store of a
 *       hundred thousand files, which is why the System Status page reads
 *       the result of an hourly scan instead of walking on request. A walk
 *       stops early at a file-count or time budget and says so
 *       ({@link Usage#complete()} false) rather than blocking a thread for
 *       an hour on a store nobody expected to be that large.</li>
 *   <li>{@link #fileSystemOf} asks the filesystem for its size and free
 *       space: one call, whatever the store holds.</li>
 * </ul>
 *
 * <p>Symbolic links are not followed: a link out of a store into another
 * one would count the same bytes twice, and a link cycle would never end.
 */
public final class DirectoryUsage {

    private DirectoryUsage() {
    }

    /**
     * What a walk found.
     *
     * @param present  the root exists and is a directory
     * @param bytes    the sum of the sizes of the regular files seen
     * @param files    how many regular files were seen
     * @param complete false when the walk stopped at a budget or could not
     *                 read part of the tree — the numbers are then a floor
     */
    public record Usage(boolean present, long bytes, long files, boolean complete) {
        static final Usage ABSENT = new Usage(false, 0L, 0L, true);
    }

    /**
     * The filesystem a path lives on.
     *
     * @param key         identifies the filesystem, so stores on the same
     *                    disk can be shown together (name, type and size of
     *                    the file store: a bind mount of a host directory
     *                    reports the host filesystem's)
     * @param type        the filesystem type as the kernel names it
     * @param totalBytes  the size of the filesystem
     * @param usableBytes what this process may still write
     */
    public record FileSystemInfo(String key, String type, long totalBytes, long usableBytes) {
    }

    /**
     * Filesystem types that live and die with the container: a store on one
     * of these is not on a mounted volume, and is lost when the container is
     * recreated.
     */
    static final Set<String> CONTAINER_LAYER_TYPES = Set.of("overlay", "overlayfs", "aufs", "tmpfs");

    /** Sum the regular files under {@code root}, within the given budgets. */
    public static Usage measure(Path root, long maxFiles, Duration maxTime) {
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return Usage.ABSENT;
        }
        final long deadline = System.nanoTime() + maxTime.toNanos();
        final long[] bytes = {0L};
        final long[] files = {0L};
        final boolean[] complete = {true};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return overBudget() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        bytes[0] += attrs.size();
                        files[0]++;
                    }
                    return overBudget() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Unreadable entry (permissions, deleted mid-walk): keep
                    // going, but the total is now a floor.
                    complete[0] = false;
                    return FileVisitResult.CONTINUE;
                }

                private boolean overBudget() {
                    if (files[0] >= maxFiles || System.nanoTime() > deadline) {
                        complete[0] = false;
                        return true;
                    }
                    return false;
                }
            });
        } catch (IOException e) {
            complete[0] = false;
        }
        return new Usage(true, bytes[0], files[0], complete[0]);
    }

    /** The filesystem under {@code path}, or null when it cannot be asked. */
    public static FileSystemInfo fileSystemOf(Path path) {
        if (path == null || !Files.exists(path)) return null;
        try {
            FileStore store = Files.getFileStore(path);
            long total = store.getTotalSpace();
            String type = store.type() == null ? "" : store.type();
            String key = store.name() + "|" + type + "|" + total;
            return new FileSystemInfo(key, type, total, store.getUsableSpace());
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** True when a store on this filesystem type would not survive the container. */
    public static boolean isContainerLayer(String fsType) {
        return fsType != null && CONTAINER_LAYER_TYPES.contains(fsType.toLowerCase(Locale.ROOT));
    }

    /**
     * Drop every path that lies inside another path of the list, keeping
     * the first entry for a path listed twice. The retinal-artifacts store
     * holds the B-scan store by default, and the app-data directory holds
     * the CRF attachments: measuring both would count those bytes twice.
     *
     * @param paths candidate roots, in the order they should be reported
     * @return the indexes of the paths to keep, in their original order
     */
    public static List<Integer> outermost(List<Path> paths) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) order.add(i);
        // Shorter paths first, so an enclosing root is decided before what it holds.
        order.sort(Comparator.comparingInt((Integer i) -> normalise(paths.get(i)).getNameCount())
                .thenComparingInt(i -> i));
        List<Path> kept = new ArrayList<>();
        List<Integer> keptIdx = new ArrayList<>();
        for (int i : order) {
            Path p = normalise(paths.get(i));
            boolean inside = false;
            for (Path k : kept) {
                if (p.startsWith(k)) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                kept.add(p);
                keptIdx.add(i);
            }
        }
        keptIdx.sort(Comparator.naturalOrder());
        return keptIdx;
    }

    private static Path normalise(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
