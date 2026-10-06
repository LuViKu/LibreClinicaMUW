/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.isolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServlet;

import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.EditDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ExportDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.RemoveDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ViewDatasetsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ExportExcelStudySubjectAuditLogServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.InitUpdateSubStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListDiscNotesForCRFDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListEventsForSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListEventsForSubjectsDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.PrintAllSiteEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.PrintDataEntryServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.PrintEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ReassignStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveSiteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ResolveDiscrepancyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.SetStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.SignStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.StudyAuditLogDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateSubStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewEventCRFContentServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewItemAuditLogServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewNotesDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewNotesServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewSectionDataEntryServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewSiteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewStudyEventsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewStudySubjectAuditLogServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewStudyUserServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.DeleteStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ViewStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.AdministrativeEditingServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CreateDiscrepancyNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CreateOneDiscrepancyNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.DoubleDataEntryServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.EnterDataForStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.InitialDataEntryServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ListStudySubjectsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.FindSubjectsDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.MarkEventCRFCompleteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.TableOfContentsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ViewDiscrepancyNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CheckCRFLocked;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.PrintCRFServlet;

/** The legacy requests that name a record by id, as a table. */
final class IsolationProbes {

    /** One request, with the ids of whichever site it is aimed at. */
    record Probe(String name, Supplier<HttpServlet> servlet, String method, String path,
            Function<IsolationFixture.Site, String[]> params, boolean writes, String precondition) {

        Probe(String name, Supplier<HttpServlet> servlet, String method, String path,
                Function<IsolationFixture.Site, String[]> params, boolean writes) {
            this(name, servlet, method, path, params, writes, null);
        }
    }

    private IsolationProbes() {}

    /**
     * Probes (see {@link #all()}) that reach another site's data or rows, for at least one site role. They
     * are asserted in {@link CrossSiteIsolationLegacyLeaksDatabaseIT}; the rest in
     * {@link CrossSiteIsolationLegacyDatabaseIT}. The causes, by servlet:
     * <ul>
     * <li>no record-level check at all: ViewEventCRF, ViewEventCRFContent, ViewItemAuditLog, PrintDataEntry,
     *     ShowFile, ViewStudyUser, Remove/SetStudyUserRole;</li>
     * <li>the study subject, event or event CRF is loaded from the request id and used before (or without)
     *     any scope check: UpdateStudySubject, EnterDataForStudyEvent, ReassignStudySubject, Remove/Restore
     *     StudySubject, Remove/Restore/DeleteStudyEvent, Remove/RestoreEventCRF.</li>
     * </ul>
     */
    static final Set<String> LEAKS = Set.of(
            "UpdateStudySubject.GET",
            "RemoveStudySubject.POST", "RestoreStudySubject.POST",
            "ReassignStudySubject.GET", "ReassignStudySubject.POST",
            "EnterDataForStudyEvent",
            "RemoveStudyEvent.GET", "RemoveStudyEvent.POST", "RestoreStudyEvent.POST", "DeleteStudyEvent.POST",
            "ViewEventCRF", "ViewEventCRFContent", "ViewItemAuditLog", "PrintDataEntry",
            "RemoveEventCRF.GET", "RemoveEventCRF.POST", "RestoreEventCRF.POST",
            "ShowFile",
            "ViewStudyUser", "RemoveStudyUserRole.POST", "SetStudyUserRole.POST");

    /**
     * Probes the harness cannot drive past their access check (a bean the servlet needs is not wired, or the
     * action needs a state this fixture does not have): static review only, see the report in the class comment
     * of {@link CrossSiteIsolationLegacyDatabaseIT}.
     */
    static final Set<String> NO_CONTROL = Set.of("ViewNotes", "ViewNotesData", "ViewDatasets", "ExportDataset",
            "EditDataset.GET", "RemoveDataset.GET", "RemoveDataset.POST", "RemoveSite.POST", "AssignUserToStudy.GET");

    private static String s(int i) {
        return String.valueOf(i);
    }

    /** A POST for a legacy list-to-confirm-to-submit flow. */
    static List<Probe> all() {
        List<Probe> p = new ArrayList<>();

        // ---- subject ------------------------------------------------------------------------
        p.add(new Probe("ViewStudySubject", ViewStudySubjectServlet::new, "GET", "/ViewStudySubject",
                x -> new String[] { "id", s(x.studySubjectId) }, false));
        p.add(new Probe("ViewStudySubjectAuditLog", ViewStudySubjectAuditLogServlet::new, "GET",
                "/ViewStudySubjectAuditLog", x -> new String[] { "id", s(x.studySubjectId) }, false));
        p.add(new Probe("ExportExcelStudySubjectAuditLog", ExportExcelStudySubjectAuditLogServlet::new, "GET",
                "/ExportExcelStudySubjectAuditLog", x -> new String[] { "id", s(x.studySubjectId) }, false));
        p.add(new Probe("SignStudySubject.GET", SignStudySubjectServlet::new, "GET", "/SignStudySubject",
                x -> new String[] { "id", s(x.studySubjectId) }, false));
        p.add(new Probe("UpdateStudySubject.GET", UpdateStudySubjectServlet::new, "GET", "/UpdateStudySubject",
                x -> new String[] { "id", s(x.studySubjectId), "action", "show" }, false));
        p.add(new Probe("RemoveStudySubject.GET", RemoveStudySubjectServlet::new, "GET", "/RemoveStudySubject",
                x -> new String[] { "action", "confirm", "id", s(x.studySubjectId), "subjectId", s(x.personId),
                        "studyId", s(x.studyId) }, false));
        p.add(new Probe("RemoveStudySubject.POST", RemoveStudySubjectServlet::new, "POST", "/RemoveStudySubject",
                x -> new String[] { "action", "submit", "id", s(x.studySubjectId), "subjectId", s(x.personId),
                        "studyId", s(x.studyId) }, true));
        p.add(new Probe("RestoreStudySubject.GET", RestoreStudySubjectServlet::new, "GET", "/RestoreStudySubject",
                x -> new String[] { "action", "confirm", "id", s(x.studySubjectId), "subjectId", s(x.personId),
                        "studyId", s(x.studyId) }, false));
        p.add(new Probe("RestoreStudySubject.POST", RestoreStudySubjectServlet::new, "POST", "/RestoreStudySubject",
                x -> new String[] { "action", "submit", "id", s(x.studySubjectId), "subjectId", s(x.personId),
                        "studyId", s(x.studyId) }, true,
                "UPDATE study_subject SET status_id = 5 WHERE study_subject_id = %SS%"));
        p.add(new Probe("ReassignStudySubject.GET", ReassignStudySubjectServlet::new, "GET", "/ReassignStudySubject",
                x -> new String[] { "id", s(x.studySubjectId) }, false));
        // Moves the subject to the parent study (study 1), taking it out of its site.
        p.add(new Probe("ReassignStudySubject.POST", ReassignStudySubjectServlet::new, "POST", "/ReassignStudySubject",
                x -> new String[] { "id", s(x.studySubjectId), "action", "submit", "studyId", "1" }, true));

        // ---- events -------------------------------------------------------------------------
        p.add(new Probe("EnterDataForStudyEvent", EnterDataForStudyEventServlet::new, "GET", "/EnterDataForStudyEvent",
                x -> new String[] { "eventId", s(x.eventId) }, false));
        p.add(new Probe("UpdateStudyEvent.GET", UpdateStudyEventServlet::new, "GET", "/UpdateStudyEvent",
                x -> new String[] { "event_id", s(x.eventId), "ss_id", s(x.studySubjectId) }, false));
        p.add(new Probe("UpdateStudyEvent.POST", UpdateStudyEventServlet::new, "POST", "/UpdateStudyEvent",
                x -> new String[] { "event_id", s(x.eventId), "ss_id", s(x.studySubjectId), "action", "submit",
                        "statusId", "3", "startDate", "01/20/2026", "startHour", "-1", "startMinute", "-1",
                        "startHalf", "", "endDate", "", "endHour", "-1", "endMinute", "-1", "endHalf", "",
                        "location", "HIJACKED-LOC" }, true));
        p.add(new Probe("RemoveStudyEvent.GET", RemoveStudyEventServlet::new, "GET", "/RemoveStudyEvent",
                x -> new String[] { "action", "confirm", "id", s(x.eventId), "studySubId", s(x.studySubjectId) },
                false));
        p.add(new Probe("RemoveStudyEvent.POST", RemoveStudyEventServlet::new, "POST", "/RemoveStudyEvent",
                x -> new String[] { "action", "submit", "id", s(x.eventId), "studySubId", s(x.studySubjectId) },
                true));
        p.add(new Probe("RestoreStudyEvent.GET", RestoreStudyEventServlet::new, "GET", "/RestoreStudyEvent",
                x -> new String[] { "action", "confirm", "id", s(x.eventId), "studySubId", s(x.studySubjectId) },
                false));
        p.add(new Probe("RestoreStudyEvent.POST", RestoreStudyEventServlet::new, "POST", "/RestoreStudyEvent",
                x -> new String[] { "action", "submit", "id", s(x.eventId), "studySubId", s(x.studySubjectId) },
                true, "UPDATE study_event SET status_id = 5 WHERE study_event_id = %EV%"));
        p.add(new Probe("DeleteStudyEvent.POST", DeleteStudyEventServlet::new, "POST", "/DeleteStudyEvent",
                x -> new String[] { "action", "submit", "id", s(x.eventId), "studySubId", s(x.studySubjectId) },
                true));
        p.add(new Probe("ViewStudyEvents", ViewStudyEventsServlet::new, "GET", "/ViewStudyEvents",
                x -> new String[] { "definitionId", "2", "statusId", "-1" }, false));
        p.add(new Probe("ListEventsForSubject", ListEventsForSubjectServlet::new, "GET", "/ListEventsForSubject",
                x -> new String[] { "defId", "2" }, false));

        // ---- event CRFs and item data ----------------------------------------------------------
        p.add(new Probe("ViewSectionDataEntry", ViewSectionDataEntryServlet::new, "GET", "/ViewSectionDataEntry",
                x -> new String[] { "ecId", s(x.eventCrfId), "tabId", "2", "sectionId", "2" }, false));
        p.add(new Probe("ViewEventCRF", ViewEventCRFServlet::new, "GET", "/ViewEventCRF",
                x -> new String[] { "id", s(x.eventCrfId), "studySubId", s(x.studySubjectId) }, false));
        p.add(new Probe("ViewEventCRFContent", ViewEventCRFContentServlet::new, "GET", "/ViewEventCRFContent",
                x -> new String[] { "ecId", s(x.eventCrfId), "id", s(x.eventCrfId), "eventId", s(x.eventId) },
                false));
        p.add(new Probe("ViewItemAuditLog", ViewItemAuditLogServlet::new, "GET", "/ViewItemAuditLog",
                x -> new String[] { "entityId", s(x.itemDataId), "auditTable", "itemdata" }, false));
        p.add(new Probe("PrintEventCRF", PrintEventCRFServlet::new, "GET", "/PrintEventCRF",
                x -> new String[] { "ecId", s(x.eventCrfId), "id", s(x.eventCrfId) }, false));
        p.add(new Probe("PrintCRF", PrintCRFServlet::new, "GET", "/PrintCRF",
                x -> new String[] { "ecId", s(x.eventCrfId), "id", s(x.eventCrfId) }, false));
        p.add(new Probe("PrintDataEntry", PrintDataEntryServlet::new, "GET", "/PrintDataEntry",
                x -> new String[] { "ecId", s(x.eventCrfId) }, false));
        p.add(new Probe("PrintAllSiteEventCRF", PrintAllSiteEventCRFServlet::new, "GET", "/PrintAllSiteEventCRF",
                x -> new String[] { "siteId", s(x.studyId) }, false));
        p.add(new Probe("TableOfContents", TableOfContentsServlet::new, "GET", "/TableOfContents",
                x -> new String[] { "ecid", s(x.eventCrfId), "eventCRFId", s(x.eventCrfId) }, false));
        p.add(new Probe("MarkEventCRFComplete.GET", MarkEventCRFCompleteServlet::new, "GET", "/MarkEventCRFComplete",
                x -> new String[] { "eventCRFId", s(x.eventCrfId) }, false));
        p.add(new Probe("RemoveEventCRF.GET", RemoveEventCRFServlet::new, "GET", "/RemoveEventCRF",
                x -> new String[] { "action", "confirm", "id", s(x.eventCrfId), "studySubId", s(x.studySubjectId) },
                false));
        p.add(new Probe("RemoveEventCRF.POST", RemoveEventCRFServlet::new, "POST", "/RemoveEventCRF",
                x -> new String[] { "action", "submit", "id", s(x.eventCrfId), "studySubId", s(x.studySubjectId) },
                true));
        p.add(new Probe("RestoreEventCRF.POST", RestoreEventCRFServlet::new, "POST", "/RestoreEventCRF",
                x -> new String[] { "action", "submit", "id", s(x.eventCrfId), "studySubId", s(x.studySubjectId) },
                true, "UPDATE event_crf SET status_id = 5 WHERE event_crf_id = %EC%"));
        p.add(new Probe("InitialDataEntry.POST", InitialDataEntryServlet::new, "POST", "/InitialDataEntry",
                x -> new String[] { "eventCRFId", s(x.eventCrfId), "sectionId", "2", "submitted", "1", "input3",
                        "999", "input4", "99.5", "input5", "199" }, true));
        p.add(new Probe("DoubleDataEntry.POST", DoubleDataEntryServlet::new, "POST", "/DoubleDataEntry",
                x -> new String[] { "eventCRFId", s(x.eventCrfId), "sectionId", "2", "submitted", "1", "input3",
                        "999", "input4", "99.5", "input5", "199" }, true,
                "UPDATE event_crf SET status_id = 4 WHERE event_crf_id = %EC%"));
        p.add(new Probe("AdministrativeEditing.POST", AdministrativeEditingServlet::new, "POST",
                "/AdministrativeEditing",
                x -> new String[] { "eventCRFId", s(x.eventCrfId), "sectionId", "2", "submitted", "1", "input3",
                        "999", "input4", "99.5", "input5", "199" }, true,
                "UPDATE event_crf SET status_id = 2 WHERE event_crf_id = %EC%"));
        p.add(new Probe("CheckCRFLocked", CheckCRFLocked::new, "GET", "/CheckCRFLocked",
                x -> new String[] { "ecId", s(x.eventCrfId), "userId", "1" }, false));

        // ---- discrepancy notes ------------------------------------------------------------------
        p.add(new Probe("ViewNote", ViewNoteServlet::new, "GET", "/ViewNote",
                x -> new String[] { "id", s(x.itemNoteId) }, false));
        p.add(new Probe("ViewDiscrepancyNote", ViewDiscrepancyNoteServlet::new, "GET", "/ViewDiscrepancyNote",
                x -> new String[] { "id", s(x.itemDataId), "name", "itemData", "column", "value", "field", "input1",
                        "writeToDB", "1", "eventCRFId", s(x.eventCrfId), "subjectId", s(x.studySubjectId) }, false));
        p.add(new Probe("ViewDiscrepancyNote.studySub", ViewDiscrepancyNoteServlet::new, "GET",
                "/ViewDiscrepancyNote",
                x -> new String[] { "id", s(x.studySubjectId), "name", "studySub", "column", "enrollment_date",
                        "field", "enrollmentDate", "writeToDB", "1", "subjectId", s(x.personId) }, false));
        p.add(new Probe("ResolveDiscrepancy", ResolveDiscrepancyServlet::new, "GET", "/ResolveDiscrepancy",
                x -> new String[] { "noteId", s(x.itemNoteId), "ecId", s(x.eventCrfId), "studySubjectId",
                        s(x.studySubjectId) }, false));
        p.add(new Probe("CreateDiscrepancyNote.POST", CreateDiscrepancyNoteServlet::new, "POST",
                "/CreateDiscrepancyNote",
                x -> new String[] { "name", "itemData", "id", s(x.itemDataId), "column", "value", "field", "input1",
                        "writeToDB", "1", "submitted", "1", "detailedDes", "", "parentId", "0", "description",
                        "HIJACKED-NOTE", "typeId", "3", "resStatusId", "1" }, true));
        p.add(new Probe("CreateOneDiscrepancyNote.POST", CreateOneDiscrepancyNoteServlet::new, "POST",
                "/CreateOneDiscrepancyNote",
                x -> new String[] { "name", "itemData", "id", s(x.itemDataId), "column", "value", "field", "input1",
                        "writeToDB", "1", "submitted", "1", "detailedDes", "", "parentId", "0", "description",
                        "HIJACKED-NOTE", "typeId", "3", "resStatusId", "1" }, true));

        // ---- lists (their scope is the session's study; they take no id) ----------------------------
        p.add(new Probe("ListStudySubjects", ListStudySubjectsServlet::new, "GET", "/ListStudySubjects",
                x -> new String[0], false));
        p.add(new Probe("FindSubjectsData", FindSubjectsDataServlet::new, "GET", "/FindSubjectsData",
                x -> new String[] { "length", "500" }, false));
        p.add(new Probe("ViewNotes", ViewNotesServlet::new, "GET", "/ViewNotes", x -> new String[0], false));
        p.add(new Probe("ViewNotesData", ViewNotesDataServlet::new, "GET", "/ViewNotesData",
                x -> new String[] { "length", "500" }, false));
        p.add(new Probe("StudyAuditLogData", StudyAuditLogDataServlet::new, "GET", "/StudyAuditLogData",
                x -> new String[] { "length", "500" }, false));
        p.add(new Probe("ListDiscNotesForCRFData", ListDiscNotesForCRFDataServlet::new, "GET",
                "/ListDiscNotesForCRFData", x -> new String[] { "length", "500", "defId", "2" }, false));
        p.add(new Probe("ListEventsForSubjectsData", ListEventsForSubjectsDataServlet::new, "GET",
                "/ListEventsForSubjectsData", x -> new String[] { "length", "500", "defId", "2" }, false));

        // ---- datasets ---------------------------------------------------------------------------
        p.add(new Probe("ViewDatasets", ViewDatasetsServlet::new, "GET", "/ViewDatasets",
                x -> new String[] { "datasetId", s(x.datasetId), "action", "details" }, false));
        p.add(new Probe("ExportDataset", ExportDatasetServlet::new, "GET", "/ExportDataset",
                x -> new String[] { "datasetId", s(x.datasetId) }, false));
        p.add(new Probe("EditDataset.GET", EditDatasetServlet::new, "GET", "/EditDataset",
                x -> new String[] { "dsId", s(x.datasetId) }, false));
        p.add(new Probe("RemoveDataset.GET", RemoveDatasetServlet::new, "GET", "/RemoveDataset",
                x -> new String[] { "dsId", s(x.datasetId), "action", "confirm" }, false));
        p.add(new Probe("RemoveDataset.POST", RemoveDatasetServlet::new, "POST", "/RemoveDataset",
                x -> new String[] { "dsId", s(x.datasetId), "action", "submit" }, true));

        p.add(new Probe("ShowFile", at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ShowFileServlet::new,
                "GET", "/ShowFile", x -> new String[] { "datasetId", s(x.datasetId), "fileId", s(x.archivedFileId) },
                false));
        p.add(new Probe("AccessFile", at.ac.meduniwien.ophthalmology.libreclinica.control.extract.AccessFileServlet::new,
                "GET", "/AccessFile", x -> new String[] { "fileId", s(x.archivedFileId) }, false));

        // ---- study, site and user management by a site user ---------------------------------------------
        p.add(new Probe("ViewStudy", ViewStudyServlet::new, "GET", "/ViewStudy",
                x -> new String[] { "id", s(x.studyId), "viewFull", "yes" }, false));
        p.add(new Probe("ViewSite", ViewSiteServlet::new, "GET", "/ViewSite",
                x -> new String[] { "id", s(x.studyId) }, false));
        p.add(new Probe("InitUpdateSubStudy", InitUpdateSubStudyServlet::new, "GET", "/InitUpdateSubStudy",
                x -> new String[] { "id", s(x.studyId) }, false));
        p.add(new Probe("RemoveSite.POST", RemoveSiteServlet::new, "POST", "/RemoveSite",
                x -> new String[] { "id", s(x.studyId), "action", "submit" }, true));
        p.add(new Probe("UpdateSubStudy.POST", UpdateSubStudyServlet::new, "POST", "/UpdateSubStudy",
                x -> new String[] { "id", s(x.studyId), "action", "submit", "name", "HIJACKED-SITE", "uniqueProId",
                        "HIJ", "prinInvestigator", "HIJACKED-PI", "startDate", "01/01/2026", "statusId", "1" }, true));
        p.add(new Probe("ListStudyUser", at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListStudyUserServlet::new,
                "GET", "/ListStudyUser", x -> new String[0], false));
        p.add(new Probe("AssignUserToStudy.GET",
                at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.AssignUserToStudyServlet::new, "GET",
                "/AssignUserToStudy", x -> new String[0], false));
        p.add(new Probe("ViewStudyUser", ViewStudyUserServlet::new, "GET", "/ViewStudyUser",
                x -> new String[] { "name", x.userName(IsolationFixture.INVESTIGATOR), "studyId", s(x.studyId) },
                false));
        p.add(new Probe("RemoveStudyUserRole.POST", RemoveStudyUserRoleServlet::new, "POST", "/RemoveStudyUserRole",
                x -> new String[] { "action", "submit", "name", x.userName(IsolationFixture.INVESTIGATOR), "studyId",
                        s(x.studyId), "roleId", "4" }, true));
        p.add(new Probe("SetStudyUserRole.POST", SetStudyUserRoleServlet::new, "POST", "/SetStudyUserRole",
                x -> new String[] { "action", "submit", "name", x.userName(IsolationFixture.INVESTIGATOR), "studyId",
                        s(x.studyId), "roleId", "2" }, true));
        return p;
    }
}
