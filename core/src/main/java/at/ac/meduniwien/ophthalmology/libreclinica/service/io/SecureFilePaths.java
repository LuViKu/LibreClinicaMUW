/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.commons.io.FilenameUtils;

/**
 * Turning client-supplied names into file paths the application is willing to
 * write to.
 *
 * <p>A multipart part carries whatever name the client chose to send. Browsers
 * send a bare file name, but nothing in the protocol requires that, and the
 * upload sinks here concatenate the name onto a directory: a name carrying
 * {@code ../} or an absolute prefix therefore gets to decide where the file
 * lands. The two steps below - reduce the name to its last segment, then
 * confirm the resulting file really is below the directory it was meant for -
 * belong together at every sink, because either one alone still leaves a gap.
 */
public final class SecureFilePaths {

    private SecureFilePaths() {
    }

    /**
     * The bare file name of a client-supplied multipart name, or {@code null}
     * when the name cannot be used as one.
     *
     * <p>{@link FilenameUtils#getName(String)} drops everything up to the last
     * separator, and it treats {@code /} and {@code \} as separators whatever
     * the platform is. That matters: the sinks used to cut at the last
     * backslash only - a comment explains this as a quirk of Internet Explorer
     * 6 and 7 sending the whole client path - which leaves a name written with
     * forward slashes untouched, and a separator surviving into the
     * concatenation is the whole problem.
     *
     * <p>{@code "."} and {@code ".."} name directories rather than files and
     * are refused, as are the empty name and any name holding a NUL character,
     * which some file APIs treat as a string terminator. The NUL test runs
     * first because {@code getName} raises {@link IllegalArgumentException} on
     * such a name, and callers here want the same {@code null} they get for
     * every other unusable name.
     */
    public static String safeUploadName(String rawName) {
        if (rawName == null || rawName.indexOf('\0') >= 0) {
            return null;
        }
        String name = FilenameUtils.getName(rawName);
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        return name;
    }

    /**
     * Whether {@code candidate} lies inside {@code base}, both resolved to
     * their canonical form first so that symbolic links and {@code ..}
     * segments are already gone.
     *
     * <p>The comparison runs over {@link Path} elements, not over the canonical
     * path as a string. Comparing strings with
     * {@link String#startsWith(String)} accepts a sibling directory whose name
     * merely begins with the base's name - {@code .../attachedX/f} passes a
     * check against {@code .../attached} - and that is a directory the caller
     * never meant to write to.
     *
     * @throws IOException if either path cannot be resolved canonically
     */
    public static boolean isInside(File candidate, File base) throws IOException {
        Path resolved = candidate.getCanonicalFile().toPath();
        Path root = base.getCanonicalFile().toPath();
        return resolved.startsWith(root);
    }
}
