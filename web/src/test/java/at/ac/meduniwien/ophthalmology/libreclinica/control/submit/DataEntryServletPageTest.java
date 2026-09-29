/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;

/**
 * Which legacy data-entry servlet is running (2026-09-29).
 *
 * <p>{@code getServletPage} has returned a String since 2012, but the
 * servlet compared it with {@code .equals(Page.X)}, an enum, which is never
 * true. Pinned here: a servlet page is recognised with or without the query
 * string the subclasses append, and conditional (SCD) items follow the form
 * in initial data entry and in administrative editing without a forced
 * reason for change, and nowhere else.
 */
class DataEntryServletPageTest {

    @Test
    void aServletPageIsRecognisedWithOrWithoutItsQueryString() {
        assertTrue(DataEntryServlet.isServletPage("/InitialDataEntry", Page.INITIAL_DATA_ENTRY_SERVLET));
        assertTrue(DataEntryServlet.isServletPage("/InitialDataEntry?eventCRFId=1200&sectionId=301&tab=2",
                Page.INITIAL_DATA_ENTRY_SERVLET));
        assertTrue(DataEntryServlet.isServletPage("/DoubleDataEntry?eventCRFId=1200&sectionId=301&tab=2",
                Page.DOUBLE_DATA_ENTRY_SERVLET));
        assertTrue(DataEntryServlet.isServletPage("/AdministrativeEditing?eventCRFId=1200&sectionId=301&tab=2&",
                Page.ADMIN_EDIT_SERVLET));
    }

    @Test
    void anotherServletOrTheJspIsNotThisServlet() {
        assertFalse(DataEntryServlet.isServletPage("/InitialDataEntry", Page.DOUBLE_DATA_ENTRY_SERVLET));
        assertFalse(DataEntryServlet.isServletPage("/ViewSectionDataEntry", Page.ADMIN_EDIT_SERVLET));
        // The heritage SCD check named the JSP constant, which no servlet page ever is.
        assertFalse(DataEntryServlet.isServletPage("/InitialDataEntry", Page.INITIAL_DATA_ENTRY));
        assertFalse(DataEntryServlet.isServletPage(null, Page.ADMIN_EDIT_SERVLET));
    }

    @Test
    void anAdministrativeEditingPageReachedFromTheNotesPageIsStillAdministrativeEditing() {
        // ResolveDiscrepancyServlet appends "?fromViewNotes=1" to the shared
        // Page constant's file name; the servlet page then carries it too.
        assertTrue(DataEntryServlet.isServletPage("/AdministrativeEditing?fromViewNotes=1&eventCRFId=5&sectionId=6&tab=1&",
                Page.ADMIN_EDIT_SERVLET));
    }

    @Test
    void conditionalItemsFollowTheFormInInitialDataEntry() {
        assertTrue(DataEntryServlet.scdDisplayFollowsForm("/InitialDataEntry?eventCRFId=1200&sectionId=301&tab=2",
                () -> fail("the reason-for-change setting is only consulted in administrative editing")));
    }

    @Test
    void conditionalItemsFollowTheFormInAdministrativeEditingOnlyWithoutAForcedReasonForChange() {
        assertTrue(DataEntryServlet.scdDisplayFollowsForm("/AdministrativeEditing?eventCRFId=1200&sectionId=301&tab=2&",
                () -> false));
        assertFalse(DataEntryServlet.scdDisplayFollowsForm("/AdministrativeEditing?eventCRFId=1200&sectionId=301&tab=2&",
                () -> true));
    }

    @Test
    void conditionalItemsWithASavedValueStayShownEverywhereElse() {
        assertFalse(DataEntryServlet.scdDisplayFollowsForm("/DoubleDataEntry?eventCRFId=1200&sectionId=301&tab=2",
                () -> false));
        assertFalse(DataEntryServlet.scdDisplayFollowsForm("/ViewSectionDataEntry", () -> false));
    }
}
