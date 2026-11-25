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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResponseType;

/**
 * Which values a coded item accepts. HealthAEye's "performed" items are
 * single-selects coded {@code Y}/{@code N}; the catalogue's old default
 * {@code 1} is not one of them.
 */
public class CodedAnswersTest {

    private static CodedAnswers.Answers yesNo() {
        return CodedAnswers.parse(ResponseType.SELECT.getId(), "Yes,No", "Y,N");
    }

    @Test
    public void aSingleSelectAcceptsOnlyItsOwnCodes() {
        CodedAnswers.Answers a = yesNo();
        assertTrue(a.coded());
        assertEquals("single-select", a.responseType());
        assertTrue(a.accepts("Y"));
        assertTrue("surrounding blanks do not matter", a.accepts(" Y "));
        assertFalse("the old default is no answer of a Y/N item", a.accepts("1"));
        assertFalse("codes are compared as stored", a.accepts("y"));
        assertFalse(a.accepts("Yes"));
        assertFalse(a.accepts(""));
        assertFalse(a.accepts(null));
        assertEquals("Y (Yes), N (No)", a.describe());
    }

    @Test
    public void radioCheckboxAndMultiSelectAreCodedToo() {
        for (ResponseType t : List.of(ResponseType.RADIO, ResponseType.CHECKBOX, ResponseType.SELECTMULTI)) {
            CodedAnswers.Answers a = CodedAnswers.parse(t.getId(), "Ja,Nein", "1,0");
            assertTrue(t.getName(), a.coded());
            assertTrue(t.getName(), a.accepts("1"));
            assertFalse(t.getName(), a.accepts("Y"));
        }
    }

    @Test
    public void aFreeTextItemAcceptsAnything() {
        CodedAnswers.Answers a = CodedAnswers.parse(ResponseType.TEXT.getId(), "", "");
        assertFalse(a.coded());
        assertTrue(a.options().isEmpty());
        assertTrue(a.accepts("1"));
        assertTrue(a.accepts("whatever"));
    }

    @Test
    public void anOptionWithoutALabelIsNamedByItsCode() {
        CodedAnswers.Answers a = CodedAnswers.parse(ResponseType.SELECT.getId(), "", "A,B");
        assertEquals("A, B", a.describe());
    }

    @Test
    public void aBindingMustFitEveryVersionThatCarriesTheItem() {
        CodedAnswers.Answers text = CodedAnswers.parse(ResponseType.TEXT.getId(), "", "");
        CodedAnswers.Answers numeric = CodedAnswers.parse(ResponseType.RADIO.getId(), "Yes,No", "1,0");

        assertTrue(CodedAnswers.acceptedEverywhere(List.of(yesNo(), text), "Y"));
        assertFalse("a code only one version has does not fit the other",
                CodedAnswers.acceptedEverywhere(List.of(yesNo(), numeric), "Y"));
        assertTrue("no version: that binding fails differently",
                CodedAnswers.acceptedEverywhere(List.of(), "1"));

        assertNull(CodedAnswers.firstCoded(List.of(text)));
        CodedAnswers.Answers first = yesNo();
        assertSame(first, CodedAnswers.firstCoded(List.of(text, first, numeric)));
    }
}
