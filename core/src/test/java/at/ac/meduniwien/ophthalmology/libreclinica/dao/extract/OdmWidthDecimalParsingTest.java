/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.extract;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Width/decimal parsing for ODM metadata.
 *
 * <p>Written after this cost the AI pilot its entire data export: several
 * Liquibase-seeded CRFs store {@code width_decimal} as {@code (4,1)} — the
 * shape of a SQL {@code NUMERIC(4,1)} declaration — while the documented
 * OpenClinica format is {@code 4(1)}. The old parser read {@code (4,1)} as the
 * "decimals only" form and called {@code Integer.parseInt("4,1")}, which threw.
 * Because ODM metadata is collected per CRF version rather than per selected
 * item, that single malformed value made every ODM export of the nAMD
 * Treat-and-Extend study impossible regardless of what the operator picked.
 *
 * <p>Two properties are pinned here: the SQL-ish spelling is understood, and
 * nothing in this parser can abort an export — width/decimal is a presentation
 * hint, so an unreadable value degrades to 0 instead of throwing.
 */
public class OdmWidthDecimalParsingTest {

    private final OdmExtractDAO dao = new OdmExtractDAO(null);

    /* ---- the documented w(d) form still works ---- */

    @Test
    public void parsesTheDocumentedWidthAndDecimalForm() {
        assertEquals(5, dao.parseWidth("5(2)"));
        assertEquals(2, dao.parseDecimal("5(2)"));
    }

    @Test
    public void parsesTheDecimalsOnlyForm() {
        assertEquals(0, dao.parseWidth("(3)"));
        assertEquals(3, dao.parseDecimal("(3)"));
    }

    @Test
    public void parsesAPlainWidth() {
        assertEquals(12, dao.parseWidth("12"));
        assertEquals(0, dao.parseDecimal("12"));
    }

    /** The literal placeholders the spreadsheet template ships with. */
    @Test
    public void treatsTheWAndDPlaceholdersAsZero() {
        assertEquals(0, dao.parseWidth("w(d)"));
        assertEquals(0, dao.parseDecimal("w(d)"));
    }

    /* ---- the seeded SQL-ish (w,d) form ---- */

    @Test
    public void parsesTheSqlStyleWidthDecimalTheSeedsWrote() {
        assertEquals(4, dao.parseWidth("(4,1)"));
        assertEquals(1, dao.parseDecimal("(4,1)"));
        assertEquals(6, dao.parseWidth("(6,4)"));
        assertEquals(4, dao.parseDecimal("(6,4)"));
        assertEquals(3, dao.parseWidth("(3,0)"));
        assertEquals(0, dao.parseDecimal("(3,0)"));
    }

    @Test
    public void parsesTheSqlStyleFormWithoutParenthesesOrWithSpacing() {
        assertEquals(4, dao.parseWidth("4,1"));
        assertEquals(1, dao.parseDecimal("4,1"));
        assertEquals(5, dao.parseWidth("( 5 , 2 )"));
        assertEquals(2, dao.parseDecimal("( 5 , 2 )"));
    }

    /* ---- robustness: a bad value must not abort the export ---- */

    @Test
    public void unreadableValuesDegradeToZeroRatherThanThrowing() {
        assertEquals(0, dao.parseWidth("not-a-number"));
        assertEquals(0, dao.parseDecimal("not-a-number"));
        assertEquals(0, dao.parseWidth("(x,y)"));
        assertEquals(0, dao.parseDecimal("(x,y)"));
        assertEquals(0, dao.parseWidth("()"));
        assertEquals(0, dao.parseDecimal("()"));
    }

    @Test
    public void nullAndBlankAreZero() {
        assertEquals(0, dao.parseWidth(null));
        assertEquals(0, dao.parseDecimal(null));
        assertEquals(0, dao.parseWidth(""));
        assertEquals(0, dao.parseDecimal(""));
    }
}
