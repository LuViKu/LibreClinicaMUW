/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DataEntryStage;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.CRFDataPostImportContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.FormDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ImportItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ImportItemGroupDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ODMContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.StudyEventDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.SubjectDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.UpsertOnBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.ImportCrfPreviewDto.ImportIssue;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.ImportCrfPreviewDto.PreviewRowDto;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemFormMetadataDAO;

/**
 * What committing an ODM import will do with each value in the file, worked
 * out without writing anything. The preview shows it; the commit works it out
 * again and refuses when the data changed in between, so what is written is
 * what the operator was shown.
 *
 * <p>The rules are those of the legacy pipeline the commit runs
 * ({@code ImportCRFDataService.fetchEventCRFBeans} and
 * {@code lookupValidationErrors}, then the save step of
 * {@code VerifyImportedCRFDataServlet}):
 * <ul>
 *   <li>A visit that is locked, signed or stopped refuses the whole file; so
 *       does a visit the subject does not have.</li>
 *   <li>A CRF not started yet is created and filled when its visit is
 *       scheduled, started or completed and the file's {@code UpsertOn}
 *       admits {@code NotStarted}; otherwise its values are skipped.</li>
 *   <li>A started CRF (initial data entry) is filled when {@code UpsertOn}
 *       admits {@code DataEntryStarted}, a completed one (data entry
 *       complete) when it admits {@code DataEntryComplete}. Any other CRF
 *       (awaiting double data entry, signed, locked, removed) is
 *       skipped.</li>
 *   <li>In a CRF that is filled, a value with no row yet is inserted and a
 *       stored value is overwritten; the same value stored already changes
 *       nothing.</li>
 * </ul>
 * {@code UpsertOn} admits all three when the file does not say.
 *
 * <p>Values the item's definition rejects (type, width, response options)
 * are found by the legacy validator only when the commit runs; the commit
 * then refuses the file and writes no value, as legacy does.
 */
final class ImportRowProjection {

    static final String READY = "ready";
    static final String OVERWRITE = "overwrite";
    static final String WARNING = "warning";
    static final String ERROR = "error";
    static final String ACTION_INSERT = "insert";
    static final String ACTION_OVERWRITE = "overwrite";
    static final String ACTION_SKIP = "skip";

    /** The rows, in file order, and the findings that refuse the file. */
    record Result(List<PreviewRowDto> rows, List<ImportIssue> issues) {

        long count(String action) {
            return rows.stream().filter(r -> action.equals(r.action())).count();
        }

        long countStatus(String status) {
            return rows.stream().filter(r -> status.equals(r.status())).count();
        }
    }

    private final StudyDAO studyDao;
    private final StudySubjectDAO studySubjectDao;
    private final StudyEventDefinitionDAO definitionDao;
    private final StudyEventDAO studyEventDao;
    private final CRFVersionDAO crfVersionDao;
    private final EventCRFDAO eventCrfDao;
    private final ItemDAO itemDao;
    private final ItemFormMetadataDAO itemFormMetadataDao;
    private final ItemDataDAO itemDataDao;
    private final Map<String, ItemBean> itemsByOid = new HashMap<>();
    private final Map<Integer, Boolean> itemsWithMetadata = new HashMap<>();

    ImportRowProjection(DataSource ds) {
        this.studyDao = new StudyDAO(ds);
        this.studySubjectDao = new StudySubjectDAO(ds);
        this.definitionDao = new StudyEventDefinitionDAO(ds);
        this.studyEventDao = new StudyEventDAO(ds);
        this.crfVersionDao = new CRFVersionDAO(ds);
        this.eventCrfDao = new EventCRFDAO(ds);
        this.itemDao = new ItemDAO(ds);
        this.itemFormMetadataDao = new ItemFormMetadataDAO(ds);
        this.itemDataDao = new ItemDataDAO(ds);
    }

    /**
     * Rows only, every one refused: for a file whose OIDs do not all resolve
     * in the study, which the metadata findings already explain.
     */
    static List<PreviewRowDto> refused(ODMContainer odm) {
        List<PreviewRowDto> rows = new ArrayList<>();
        forEachValue(odm, (subject, event, form, group, item) -> rows.add(row(ERROR, ACTION_SKIP,
                subject, event, form, group, item, null, null)));
        return rows;
    }

    Result project(ODMContainer odm) {
        List<PreviewRowDto> rows = new ArrayList<>();
        List<ImportIssue> issues = new ArrayList<>();
        CRFDataPostImportContainer container = odm == null ? null : odm.getCrfDataPostImportContainer();
        if (container == null || container.getSubjectData() == null) return new Result(rows, issues);
        UpsertOnBean upsert = container.getUpsertOn() == null ? new UpsertOnBean() : container.getUpsertOn();
        StudyBean study = studyDao.findByOid(container.getStudyOID());

        for (SubjectDataBean subject : container.getSubjectData()) {
            if (subject == null || subject.getStudyEventData() == null) continue;
            StudySubjectBean studySubject = study == null ? null
                    : studySubjectDao.findByOidAndStudy(subject.getSubjectOID(), study.getId());
            for (StudyEventDataBean event : subject.getStudyEventData()) {
                if (event == null || event.getFormData() == null) continue;
                String refusal = null;
                StudyEventBean visit = null;
                StudyEventDefinitionBean definition = study == null ? null
                        : definitionDao.findByOidAndStudy(event.getStudyEventOID(), study.getId(),
                                study.getParentStudyId());
                if (studySubject == null || studySubject.getId() == 0
                        || definition == null || definition.getId() == 0) {
                    refusal = "The subject or the event is not defined in this study.";
                } else {
                    visit = (StudyEventBean) studyEventDao.findByStudySubjectIdAndDefinitionIdAndOrdinal(
                            studySubject.getId(), definition.getId(), ordinal(event.getStudyEventRepeatKey()));
                    if (visit == null || visit.getId() == 0 || visit.getSubjectEventStatus() == null) {
                        refusal = "Subject " + subject.getSubjectOID() + " has no visit "
                                + eventLabel(event) + "; schedule it before importing.";
                    } else if (isClosed(visit.getSubjectEventStatus())) {
                        refusal = "Visit " + eventLabel(event) + " of subject " + subject.getSubjectOID()
                                + " is " + visit.getSubjectEventStatus().getName()
                                + "; no data can be imported into it, so the file is refused.";
                    }
                }
                if (refusal != null) {
                    issues.add(new ImportIssue("row", subject.getSubjectOID() + " · " + eventLabel(event),
                            "ERROR", refusal));
                }
                for (FormDataBean form : event.getFormData()) {
                    if (form == null || form.getItemGroupData() == null) continue;
                    projectForm(rows, issues, upsert, subject, event, form, studySubject, visit, refusal);
                }
            }
        }
        return new Result(rows, issues);
    }

    private void projectForm(List<PreviewRowDto> rows, List<ImportIssue> issues, UpsertOnBean upsert,
                             SubjectDataBean subject, StudyEventDataBean event, FormDataBean form,
                             StudySubjectBean studySubject, StudyEventBean visit, String refusal) {
        if (refusal != null) {
            forEachValue(subject, event, form, (g, i) -> rows.add(row(ERROR, ACTION_SKIP,
                    subject, event, form, g, i, null, refusal)));
            return;
        }
        List<CRFVersionBean> versions = crfVersionDao.findAllByOid(form.getFormOID());
        if (versions == null || versions.isEmpty()) {
            forEachValue(subject, event, form, (g, i) -> rows.add(row(ERROR, ACTION_SKIP,
                    subject, event, form, g, i, null, "Unknown CRF version " + form.getFormOID())));
            return;
        }
        CRFVersionBean version = versions.get(0);
        List<EventCRFBean> existing = eventCrfDao.findByEventSubjectVersion(visit, studySubject, version);

        if (existing == null || existing.isEmpty()) {
            SubjectEventStatus s = visit.getSubjectEventStatus();
            boolean opens = upsert.isNotStarted() && (s.equals(SubjectEventStatus.SCHEDULED)
                    || s.equals(SubjectEventStatus.DATA_ENTRY_STARTED) || s.equals(SubjectEventStatus.COMPLETED));
            if (!opens) {
                String skip = upsert.isNotStarted()
                        ? "The CRF has not been started and its visit is " + s.getName()
                            + "; the import only starts CRFs of scheduled, started or completed visits."
                        : "The CRF has not been started and the file's UpsertOn excludes NotStarted.";
                forEachValue(subject, event, form, (g, i) -> rows.add(row(WARNING, ACTION_SKIP,
                        subject, event, form, g, i, null, skip)));
                return;
            }
            forEachValue(subject, event, form, (g, i) -> {
                String unknown = unknownOid(g, i);
                if (unknown != null) {
                    rows.add(row(ERROR, ACTION_SKIP, subject, event, form, g, i, null, unknown));
                    issues.add(new ImportIssue("row", subject.getSubjectOID() + " · " + eventLabel(event)
                            + " · " + form.getFormOID(), "ERROR", unknown));
                } else {
                    rows.add(row(READY, ACTION_INSERT, subject, event, form, g, i, null, null));
                }
            });
            return;
        }

        // The event CRF the legacy validator fills: the one of this visit
        // and version.
        EventCRFBean ecb = eventCrfDao.findByEventCrfVersion(visit, version);
        if (ecb == null) ecb = existing.get(0);
        DataEntryStage stage = ecb.getStage();
        boolean fills = (upsert.isDataEntryStarted() && DataEntryStage.INITIAL_DATA_ENTRY.equals(stage))
                || (upsert.isDataEntryComplete() && DataEntryStage.DOUBLE_DATA_ENTRY_COMPLETE.equals(stage));
        if (!fills) {
            String skip = "The CRF is " + describe(ecb) + "; the import does not write into it.";
            forEachValue(subject, event, form, (g, i) -> rows.add(row(WARNING, ACTION_SKIP,
                    subject, event, form, g, i, null, skip)));
            return;
        }
        int eventCrfId = ecb.getId();
        forEachValue(subject, event, form, (g, i) -> {
            String unknown = unknownOid(g, i);
            if (unknown != null) {
                rows.add(row(ERROR, ACTION_SKIP, subject, event, form, g, i, null, unknown));
                issues.add(new ImportIssue("row", subject.getSubjectOID() + " · " + eventLabel(event)
                        + " · " + form.getFormOID(), "ERROR", unknown));
                return;
            }
            ItemBean item = item(i.getItemOID());
            ItemDataBean stored = itemDataDao.findByItemIdAndEventCRFIdAndOrdinal(
                    item.getId(), eventCrfId, ordinal(g.getItemGroupRepeatKey()));
            if (stored.getStatus() == null) {
                rows.add(row(READY, ACTION_INSERT, subject, event, form, g, i, null, null));
            } else if (nullToBlank(stored.getValue()).equals(nullToBlank(i.getValue()))) {
                rows.add(row(READY, ACTION_SKIP, subject, event, form, g, i, stored.getValue(),
                        "The same value is stored already; nothing changes."));
            } else {
                rows.add(row(OVERWRITE, ACTION_OVERWRITE, subject, event, form, g, i,
                        nullToBlank(stored.getValue()), null));
            }
        });
    }

    private ItemBean item(String oid) {
        if (oid == null) return null;
        return itemsByOid.computeIfAbsent(oid, o -> {
            List<ItemBean> found = itemDao.findByOid(o);
            return found == null || found.isEmpty() ? null : found.get(0);
        });
    }

    /**
     * Why the legacy validator would stop on this value, or null. It stops
     * the whole file on an item it cannot find, or whose form metadata it
     * cannot read ({@code ItemFormMetadataDAO.findAllByItemId}, which needs
     * the item in an item group), neither of which its metadata check before
     * looks for. (Its item-group check compares the DAO's result with null,
     * which that DAO never returns, so an unknown group stops nothing.)
     */
    private String unknownOid(ImportItemGroupDataBean group, ImportItemDataBean value) {
        ItemBean item = item(value.getItemOID());
        if (item == null) return "Unknown item " + value.getItemOID();
        boolean hasMetadata = itemsWithMetadata.computeIfAbsent(item.getId(),
                id -> !itemFormMetadataDao.findAllByItemId(id).isEmpty());
        return hasMetadata ? null
                : "Item " + value.getItemOID() + " has no form metadata in an item group; "
                        + "the import cannot check its value.";
    }

    /** Locked, signed and stopped visits refuse the file (fetchEventCRFBeans returns null). */
    private static boolean isClosed(SubjectEventStatus s) {
        return s.equals(SubjectEventStatus.LOCKED) || s.equals(SubjectEventStatus.SIGNED)
                || s.equals(SubjectEventStatus.STOPPED);
    }

    private static String describe(EventCRFBean ecb) {
        String status = ecb.getStatus() == null ? "" : ecb.getStatus().getName();
        String stage = ecb.getStage() == null ? "" : ecb.getStage().getName();
        return stage.isBlank() || "invalid".equalsIgnoreCase(stage) ? status : stage;
    }

    /** A repeat key as the legacy validator reads it: a number, else 1. */
    static int ordinal(String repeatKey) {
        if (repeatKey == null) return 1;
        try {
            return Integer.parseInt(repeatKey.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /* ----------------------------------------------------------------- */
    /* One row per ItemData leaf, in file order                          */
    /* ----------------------------------------------------------------- */

    @FunctionalInterface
    private interface LeafVisitor {
        void visit(SubjectDataBean subject, StudyEventDataBean event, FormDataBean form,
                   ImportItemGroupDataBean group, ImportItemDataBean item);
    }

    @FunctionalInterface
    private interface FormLeafVisitor {
        void visit(ImportItemGroupDataBean group, ImportItemDataBean item);
    }

    private static void forEachValue(ODMContainer odm, LeafVisitor visitor) {
        CRFDataPostImportContainer container = odm == null ? null : odm.getCrfDataPostImportContainer();
        if (container == null || container.getSubjectData() == null) return;
        for (SubjectDataBean subject : container.getSubjectData()) {
            if (subject == null || subject.getStudyEventData() == null) continue;
            for (StudyEventDataBean event : subject.getStudyEventData()) {
                if (event == null || event.getFormData() == null) continue;
                for (FormDataBean form : event.getFormData()) {
                    if (form == null || form.getItemGroupData() == null) continue;
                    forEachValue(subject, event, form, (g, i) -> visitor.visit(subject, event, form, g, i));
                }
            }
        }
    }

    private static void forEachValue(SubjectDataBean subject, StudyEventDataBean event, FormDataBean form,
                                     FormLeafVisitor visitor) {
        for (ImportItemGroupDataBean group : form.getItemGroupData()) {
            if (group == null || group.getItemData() == null) continue;
            for (ImportItemDataBean item : group.getItemData()) {
                if (item != null) visitor.visit(group, item);
            }
        }
    }

    private static PreviewRowDto row(String status, String action, SubjectDataBean subject,
                                     StudyEventDataBean event, FormDataBean form,
                                     ImportItemGroupDataBean group, ImportItemDataBean item,
                                     String before, String detail) {
        String groupOid = nullToBlank(group.getItemGroupOID());
        String groupRepeat = nullToBlank(group.getItemGroupRepeatKey());
        String itemOid = groupOid.isEmpty()
                ? nullToBlank(item.getItemOID())
                : groupOid + (groupRepeat.isEmpty() ? "" : "[" + groupRepeat + "]")
                        + " · " + nullToBlank(item.getItemOID());
        return new PreviewRowDto(status, action, nullToBlank(subject.getSubjectOID()), eventLabel(event),
                nullToBlank(form.getFormOID()), itemOid, before, nullToBlank(item.getValue()), detail);
    }

    private static String eventLabel(StudyEventDataBean event) {
        String oid = nullToBlank(event.getStudyEventOID());
        String repeat = nullToBlank(event.getStudyEventRepeatKey());
        return repeat.isEmpty() ? oid : oid + "[" + repeat + "]";
    }

    private static String nullToBlank(String s) {
        return s == null ? "" : s;
    }
}
