/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Multipart file names arrive as the client wrote them. The upload sinks join
 * them onto a directory, so what survives here decides where a file lands: a
 * name has to come out as a bare file name, and the file built from it has to
 * stay in the directory it was meant for.
 */
public class SecureFilePathsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void plainFileNameIsKept() {
        assertEquals("consent_v2.pdf", SecureFilePaths.safeUploadName("consent_v2.pdf"));
        assertEquals("visual acuity.xls", SecureFilePaths.safeUploadName("visual acuity.xls"));
    }

    @Test
    public void forwardSlashNameIsReducedToItsLastSegment() {
        assertEquals("consent.pdf", SecureFilePaths.safeUploadName("some/dir/consent.pdf"));
    }

    @Test
    public void backslashNameIsReducedToItsLastSegment() {
        assertEquals("consent.pdf", SecureFilePaths.safeUploadName("C:\\Users\\coordinator\\consent.pdf"));
    }

    @Test
    public void absoluteLookingNameIsReducedToItsLastSegment() {
        assertEquals("authorized_keys", SecureFilePaths.safeUploadName("/home/tomcat/.ssh/authorized_keys"));
    }

    @Test
    public void namesThatAreNotFileNamesReturnNull() {
        assertNull(SecureFilePaths.safeUploadName(null));
        assertNull(SecureFilePaths.safeUploadName(""));
        assertNull(SecureFilePaths.safeUploadName("."));
        assertNull(SecureFilePaths.safeUploadName(".."));
        assertNull(SecureFilePaths.safeUploadName("some/dir/.."));
        assertNull(SecureFilePaths.safeUploadName("consent.pdf\0.jsp"));
    }

    @Test
    public void traversalNameCannotLeaveTheUploadDirectory() throws Exception {
        File uploadDir = tmp.newFolder("attached");

        String windowsStyle = SecureFilePaths.safeUploadName("..\\..\\webapps\\ROOT\\shell.jsp");
        assertEquals("shell.jsp", windowsStyle);
        assertTrue(SecureFilePaths.isInside(new File(uploadDir, windowsStyle), uploadDir));

        String unixStyle = SecureFilePaths.safeUploadName("../../webapps/ROOT/shell.jsp");
        assertEquals("shell.jsp", unixStyle);
        assertTrue(SecureFilePaths.isInside(new File(uploadDir, unixStyle), uploadDir));
    }

    @Test
    public void fileBelowTheBaseDirectoryIsInside() throws Exception {
        File base = tmp.newFolder("attached");
        assertTrue(SecureFilePaths.isInside(new File(base, "consent.pdf"), base));
        assertTrue(SecureFilePaths.isInside(new File(new File(base, "S_STUDY"), "consent.pdf"), base));
    }

    @Test
    public void siblingDirectorySharingThePrefixIsNotInside() throws Exception {
        File base = tmp.newFolder("attached");
        File sibling = tmp.newFolder("attachedX");

        assertFalse(SecureFilePaths.isInside(new File(sibling, "consent.pdf"), base));
    }

    @Test
    public void parentDirectoryIsNotInside() throws Exception {
        File base = tmp.newFolder("attached");
        File outside = new File(base, ".." + File.separator + "outside.txt");

        assertFalse(SecureFilePaths.isInside(outside, base));
    }
}
