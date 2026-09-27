/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.time.LocalDateTime;

import org.junit.Test;

/**
 * The file reference an audit row carries: which file, without whose.
 */
public class IngestFileReferenceTest {

    private static final LocalDateTime RECEIVED = LocalDateTime.of(2026, 9, 24, 11, 58, 31);
    private static final String SHA = "3f2a9c1b7e4d5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b";

    @Test
    public void namesTheFileByWhatDoesNotChangeAndIdentifiesNobody() {
        assertEquals("file #482 · remidio · image · OD · received 2026-09-24 11:58 UTC · sha256 3f2a9c1b7e4d",
                IngestFileReference.format(482, "remidio", null, "upload", "image", "OD", RECEIVED, SHA));
    }

    @Test
    public void fallsBackFromDeviceToCallingAeTitleToRoute() {
        assertEquals("file #7 · OPTOMEDLUMO · dicom",
                IngestFileReference.format(7, " ", "OPTOMEDLUMO", "dicom", "dicom", null, null, null));
        assertEquals("file #8 · upload · e2e",
                IngestFileReference.format(8, null, null, "upload", "e2e", "", null, null));
    }

    @Test
    public void anOldRowWithNothingButItsIdStillReads() {
        assertEquals("file #9", IngestFileReference.format(9, null, null, null, null, null, null, null));
    }

    @Test
    public void aShortChecksumIsKeptWhole() {
        assertEquals("file #10 · sha256 abc",
                IngestFileReference.format(10, null, null, null, null, null, null, "abc"));
    }

    @Test
    public void fitsTheAuditColumn() {
        String longest = IngestFileReference.format(Long.MAX_VALUE, "d".repeat(64), null, null,
                "k".repeat(16), "OD", RECEIVED, SHA);
        assertFalse("entity_name is VARCHAR(500)", longest.length() > 500);
    }
}
