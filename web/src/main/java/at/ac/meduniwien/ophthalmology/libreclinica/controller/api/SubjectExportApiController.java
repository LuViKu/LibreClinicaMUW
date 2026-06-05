/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Phase E.6 — Data Export Phase 5 — per-subject one-click snapshot.
 *
 * <p>The legacy export tool ({@code /CreateDataset} +
 * {@code /ExportDataset}) requires creating a dataset before download.
 * That works for a multi-subject analytic extract, but it's too many
 * clicks for the common case "Investigator wants this subject's data
 * for the patient file or for transfer to a referring physician".
 *
 * <p>This controller offers a single POST per subject that streams a
 * snapshot of ALL events × ALL CRFs × ALL items for that one subject
 * in one of three formats:
 * <ul>
 *   <li><strong>odm</strong> — CDISC ODM 1.3 XML (single
 *       {@code SubjectData} block; reuses the legacy ODM extract
 *       shapes for forward-compatibility with downstream consumers).</li>
 *   <li><strong>csv</strong> — one row per event-CRF, columns = item
 *       OIDs encountered in that subject's data, values = item_data
 *       value text. Handy for spreadsheet open-and-look.</li>
 *   <li><strong>pdf</strong> — per-event section with tabular
 *       (item, value, units) rendering; subject header (label / DOB /
 *       gender) at the top. Intended for archival / patient file.</li>
 * </ul>
 *
 * <p><strong>Audit:</strong> exactly one {@code audit_log_event} row
 * per successful download with type 53
 * ({@code subject_data_exported}, seeded by
 * {@code lc-muw-2026-06-05-audit-event-type-subject-export.xml}). The
 * format is recorded in the row's {@code new_value} column so the
 * Audit Log SPA can disambiguate which format was downloaded without
 * extending the schema.
 *
 * <p><strong>Authorization:</strong> same gate as
 * {@link SubjectsApiController}'s read paths — chain-level
 * {@code .anyRequest().hasRole("USER")} plus the per-request
 * {@link SiteVisibilityFilter} scope guard. Investigators, Monitors,
 * Data Managers, Study Directors and Admins on the bound study can
 * all download data for subjects they can already read; we don't
 * narrow further here because the legacy ExportDatasetServlet has
 * never narrowed beyond "you can see the subject → you can download".
 *
 * <p><strong>Lookup key:</strong> the path uses
 * {@code (studyOid, label)} not {@code studySubjectOid} because the
 * SPA's matrix already knows {@code subject.id} = the human label.
 * Doing the lookup by label means the SPA doesn't have to fetch the
 * detail DTO just to learn the OID before triggering the download.
 */
@RestController
@RequestMapping("/api/v1/studies")
@Tag(name = "Subject Export",
     description = "Per-subject one-click data snapshot (ODM / CSV / PDF).")
public class SubjectExportApiController {

    private static final Logger LOG = LoggerFactory.getLogger(SubjectExportApiController.class);

    /**
     * {@code audit_log_event_type.audit_log_event_type_id} for the
     * per-subject snapshot, seeded by
     * {@code lc-muw-2026-06-05-audit-event-type-subject-export.xml}.
     * Defensive note: Phase 6 may add a sibling type around the same
     * cohort; we pick 53 explicitly and the Liquibase preCondition
     * keeps the bootstrap safe even if Phase 6 races us.
     */
    static final int AUDIT_TYPE_SUBJECT_DATA_EXPORTED = 53;

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    @Autowired
    public SubjectExportApiController(@Qualifier("dataSource") DataSource dataSource,
                                      SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    /**
     * Body shape — {@code format: 'odm' | 'csv' | 'pdf'}. Anything
     * else returns 400.
     */
    public record ExportRequest(String format) {}

    @PostMapping(value = "/{studyOid}/subjects/{label}/export",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> export(@PathVariable("studyOid") String studyOid,
                                    @PathVariable("label") String label,
                                    @RequestBody(required = false) ExportRequest body,
                                    HttpSession session) {
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountBean currentUser = (UserAccountBean) session.getAttribute("userBean");
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");

        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound to the session — visit /MainMenu after login."
            ));
        }
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        if (body == null || body.format() == null || body.format().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Missing 'format' — must be one of 'odm', 'csv', 'pdf'."
            ));
        }
        String fmt = body.format().trim().toLowerCase(Locale.ROOT);
        if (!fmt.equals("odm") && !fmt.equals("csv") && !fmt.equals("pdf")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Unsupported format '" + body.format() + "' — must be 'odm', 'csv' or 'pdf'."
            ));
        }

        // ---- Resolve the study scope (path param trumps session for
        //      defence-in-depth) and the study_subject row ----
        StudyDAO studyDAO = new StudyDAO(dataSource);
        StudyBean pathStudy = studyDAO.findByOid(studyOid);
        if (pathStudy == null || pathStudy.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Study with OID '" + studyOid + "' not found."
            ));
        }

        // The caller's study attribute in session may differ from the
        // path study (legacy nested-site selection). We use the path
        // study for the actual subject lookup but enforce the
        // site-visibility filter using the SESSION study so the user
        // can't sidestep their bound scope by crafting a different
        // path. A cross-study attempt yields 403, not 404.
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                currentUser, currentStudy, currentRole);
        if (!visibleStudyIds.contains(pathStudy.getId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "Study is not in your active study tree."
            ));
        }

        StudySubjectDAO studySubjectDAO = new StudySubjectDAO(dataSource);
        StudySubjectBean ss = studySubjectDAO.findByLabelAndStudy(label, pathStudy);
        if (ss == null || ss.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Subject with label '" + label + "' not found in study '"
                            + studyOid + "'."
            ));
        }

        SubjectDAO subjectDAO = new SubjectDAO(dataSource);
        SubjectBean subj = subjectDAO.findByPK(ss.getSubjectId());

        // ---- Walk the casebook in memory: every event, every event_crf,
        //      every item_data. The walk produces a uniform shape both
        //      formats render from, keeping the per-format branches
        //      independent of DAO call sequencing.
        CasebookSnapshot snapshot;
        try {
            snapshot = collectCasebook(ss, subj, pathStudy);
        } catch (Exception e) {
            LOG.error("Casebook walk failed for subject {} (study {})", ss.getOid(), pathStudy.getOid(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to assemble casebook — see server log."
            ));
        }

        byte[] payload;
        String contentType;
        String extension;
        try {
            switch (fmt) {
                case "odm":
                    payload = renderOdm(snapshot);
                    contentType = "application/xml";
                    extension = "xml";
                    break;
                case "csv":
                    payload = renderCsv(snapshot);
                    contentType = "text/csv";
                    extension = "csv";
                    break;
                case "pdf":
                    payload = renderPdf(snapshot);
                    contentType = "application/pdf";
                    extension = "pdf";
                    break;
                default:
                    // unreachable — validated above
                    return ResponseEntity.badRequest().body(Map.of("message", "Unsupported format"));
            }
        } catch (Exception e) {
            LOG.error("Render failed (fmt={}) for subject {}", fmt, ss.getOid(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to render " + fmt.toUpperCase(Locale.ROOT)
                            + " export — see server log."
            ));
        }

        // ---- Audit (best-effort; do NOT roll back the download on
        //      audit-write failure — the data is already in the user's
        //      hand the instant the body is streamed) ----
        emitExportAudit(currentUser.getId(), ss.getId(), ss.getLabel(), fmt);

        String filename = sanitizeFilename(ss.getLabel())
                + "_" + fmt
                + "_" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "." + extension;

        LOG.info("Subject export: subject {} (label={}) study {} format={} bytes={} by user={}",
                ss.getOid(), ss.getLabel(), pathStudy.getOid(), fmt, payload.length, currentUser.getName());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(payload);
    }

    /* =============================================================== */
    /* Casebook walk                                                   */
    /* =============================================================== */

    /**
     * In-memory snapshot of a single subject's whole data — built once
     * by {@link #collectCasebook} and consumed by every renderer.
     *
     * <p>{@code events} is in protocol order (definition ordinal);
     * each event's {@code crfs} is in event_crf id order; each CRF's
     * {@code items} is in the order returned by
     * {@link ItemDataDAO#findAllByEventCRFId} — which mirrors the
     * order the data was entered.
     */
    record CasebookSnapshot(
            StudySubjectBean studySubject,
            SubjectBean subject,
            StudyBean study,
            List<EventSnapshot> events) {}

    record EventSnapshot(
            StudyEventBean event,
            StudyEventDefinitionBean definition,
            List<CrfSnapshot> crfs) {}

    record CrfSnapshot(
            EventCRFBean eventCrf,
            CRFVersionBean crfVersion,
            String crfName,
            List<ItemSnapshot> items) {}

    record ItemSnapshot(
            ItemDataBean data,
            ItemBean item) {}

    private CasebookSnapshot collectCasebook(StudySubjectBean ss, SubjectBean subj, StudyBean study) {
        StudyEventDAO studyEventDAO = new StudyEventDAO(dataSource);
        StudyEventDefinitionDAO studyEventDefinitionDAO = new StudyEventDefinitionDAO(dataSource);
        EventCRFDAO eventCRFDAO = new EventCRFDAO(dataSource);
        ItemDataDAO itemDataDAO = new ItemDataDAO(dataSource);
        ItemDAO itemDAO = new ItemDAO(dataSource);
        CRFVersionDAO crfVersionDAO = new CRFVersionDAO(dataSource);
        CRFDAO crfDAO = new CRFDAO(dataSource);

        // Caches to avoid hammering single-PK DAOs for the (likely small)
        // set of items + CRF versions in one subject's casebook.
        Map<Integer, ItemBean> itemCache = new HashMap<>();
        Map<Integer, StudyEventDefinitionBean> defCache = new HashMap<>();
        Map<Integer, CRFVersionBean> versionCache = new HashMap<>();
        Map<Integer, String> crfNameCache = new HashMap<>();

        List<StudyEventBean> events = studyEventDAO.findAllByStudySubject(ss);
        if (events == null) events = Collections.emptyList();

        List<EventSnapshot> eventSnaps = new ArrayList<>(events.size());
        for (StudyEventBean ev : events) {
            StudyEventDefinitionBean def = defCache.computeIfAbsent(
                    ev.getStudyEventDefinitionId(), studyEventDefinitionDAO::findByPK);

            List<EventCRFBean> ecs = eventCRFDAO.findAllByStudyEvent(ev);
            if (ecs == null) ecs = Collections.emptyList();

            List<CrfSnapshot> crfSnaps = new ArrayList<>(ecs.size());
            for (EventCRFBean ec : ecs) {
                CRFVersionBean ver = versionCache.computeIfAbsent(
                        ec.getCRFVersionId(), crfVersionDAO::findByPK);
                String crfName = (ver == null) ? "(unknown CRF)"
                        : crfNameCache.computeIfAbsent(ver.getCrfId(), crfId -> {
                            try {
                                CRFBean crf = crfDAO.findByPK(crfId);
                                return (crf == null || crf.getName() == null) ? "(unknown CRF)" : crf.getName();
                            } catch (Exception e) {
                                return "(unknown CRF)";
                            }
                        });

                List<ItemDataBean> dataRows = itemDataDAO.findAllByEventCRFId(ec.getId());
                if (dataRows == null) dataRows = Collections.emptyList();

                List<ItemSnapshot> itemSnaps = new ArrayList<>(dataRows.size());
                for (ItemDataBean d : dataRows) {
                    ItemBean ib = itemCache.computeIfAbsent(d.getItemId(), itemDAO::findByPK);
                    itemSnaps.add(new ItemSnapshot(d, ib));
                }
                crfSnaps.add(new CrfSnapshot(ec, ver, crfName, itemSnaps));
            }

            eventSnaps.add(new EventSnapshot(ev, def, crfSnaps));
        }

        // Sort events by protocol order (definition ordinal); null
        // definitions (data corruption) sort last.
        eventSnaps.sort(Comparator.comparingInt(es -> es.definition() == null
                ? Integer.MAX_VALUE
                : es.definition().getOrdinal()));

        return new CasebookSnapshot(ss, subj, study, eventSnaps);
    }

    /* =============================================================== */
    /* ODM 1.3 renderer (hand-built XML)                               */
    /* =============================================================== */

    /**
     * Build a minimal ODM 1.3 XML document with one {@code SubjectData}
     * block. We hand-build the XML rather than plug into the legacy
     * {@code OdmDataCollector} / {@code ClinicalDataUnit} pipeline because
     * that pipeline is dataset-driven (requires a persisted
     * {@code DatasetBean}) and entangled with the legacy
     * {@code ExportDataset} servlet's session state.
     *
     * <p>Shape (per CDISC ODM 1.3):
     * <pre>
     *   &lt;ODM ...&gt;
     *     &lt;ClinicalData StudyOID=... MetaDataVersionOID="v1.0.0"&gt;
     *       &lt;SubjectData SubjectKey=...&gt;
     *         &lt;StudyEventData StudyEventOID=... StudyEventRepeatKey="1"&gt;
     *           &lt;FormData FormOID=... FormRepeatKey="1"&gt;
     *             &lt;ItemGroupData ItemGroupOID=... ItemGroupRepeatKey="1"&gt;
     *               &lt;ItemData ItemOID=... Value=... /&gt;
     *             &lt;/ItemGroupData&gt;
     *           &lt;/FormData&gt;
     *         &lt;/StudyEventData&gt;
     *       &lt;/SubjectData&gt;
     *     &lt;/ClinicalData&gt;
     *   &lt;/ODM&gt;
     * </pre>
     *
     * <p>ItemGroup is collapsed to a single per-CRF group ({@code IG_}
     * + CRF version OID) because the SPA-side seed data doesn't track
     * grouping per item. Downstream consumers that require strict
     * group fidelity should use the legacy dataset-driven export.
     */
    private byte[] renderOdm(CasebookSnapshot snap) {
        StringBuilder sb = new StringBuilder(8192);
        String createdAt = java.time.OffsetDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\"")
          .append(" xmlns:OpenClinica=\"http://www.openclinica.org/ns/odm_ext_v130/v3.1\"")
          .append(" ODMVersion=\"1.3\"")
          .append(" FileType=\"Snapshot\"")
          .append(" FileOID=\"").append(escAttr(snap.studySubject().getOid())).append("_subject_export_")
          .append(System.currentTimeMillis()).append("\"")
          .append(" CreationDateTime=\"").append(escAttr(createdAt)).append("\">\n");

        sb.append("  <ClinicalData StudyOID=\"").append(escAttr(snap.study().getOid()))
          .append("\" MetaDataVersionOID=\"v1.0.0\">\n");

        sb.append("    <SubjectData SubjectKey=\"").append(escAttr(snap.studySubject().getOid())).append("\"")
          .append(" OpenClinica:StudySubjectID=\"").append(escAttr(snap.studySubject().getLabel())).append("\">\n");

        int seqEvent = 0;
        for (EventSnapshot es : snap.events()) {
            seqEvent++;
            String evtOid = (es.definition() == null) ? "SE_UNKNOWN" : es.definition().getOid();
            sb.append("      <StudyEventData StudyEventOID=\"").append(escAttr(evtOid)).append("\"")
              .append(" StudyEventRepeatKey=\"").append(seqEvent).append("\">\n");

            int seqForm = 0;
            for (CrfSnapshot cs : es.crfs()) {
                seqForm++;
                String formOid = (cs.crfVersion() == null) ? "F_UNKNOWN" : cs.crfVersion().getOid();
                sb.append("        <FormData FormOID=\"").append(escAttr(formOid)).append("\"")
                  .append(" FormRepeatKey=\"").append(seqForm).append("\">\n");

                String groupOid = "IG_" + (cs.crfVersion() == null
                        ? ("" + cs.eventCrf().getId())
                        : cs.crfVersion().getOid());
                sb.append("          <ItemGroupData ItemGroupOID=\"").append(escAttr(groupOid)).append("\"")
                  .append(" ItemGroupRepeatKey=\"1\"")
                  .append(" TransactionType=\"Insert\">\n");

                for (ItemSnapshot is : cs.items()) {
                    String itemOid = (is.item() == null || is.item().getOid() == null)
                            ? ("I_" + is.data().getItemId())
                            : is.item().getOid();
                    String value = is.data().getValue() == null ? "" : is.data().getValue();
                    sb.append("            <ItemData ItemOID=\"").append(escAttr(itemOid))
                      .append("\" Value=\"").append(escAttr(value)).append("\"/>\n");
                }

                sb.append("          </ItemGroupData>\n");
                sb.append("        </FormData>\n");
            }

            sb.append("      </StudyEventData>\n");
        }

        sb.append("    </SubjectData>\n");
        sb.append("  </ClinicalData>\n");
        sb.append("</ODM>\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /* =============================================================== */
    /* CSV renderer                                                     */
    /* =============================================================== */

    /**
     * One row per event-CRF; columns = union of item OIDs encountered
     * in this subject's data (sorted alphabetically for predictable
     * diffs). Fixed leading columns: {@code subjectLabel},
     * {@code eventOid}, {@code eventOrdinal}, {@code crfName},
     * {@code crfStatus}, {@code dateCompleted}.
     *
     * <p>Hand-rolled because OpenCSV is not a direct dependency of
     * the web module and the data shape is straightforward — adding
     * a transitive just for a six-line writer would be overkill.
     */
    private byte[] renderCsv(CasebookSnapshot snap) {
        // Collect all item OIDs in this subject's data first so columns
        // line up across rows. Sort alphabetically for deterministic output.
        java.util.TreeSet<String> allItemOids = new java.util.TreeSet<>();
        for (EventSnapshot es : snap.events()) {
            for (CrfSnapshot cs : es.crfs()) {
                for (ItemSnapshot is : cs.items()) {
                    allItemOids.add(itemOidFor(is));
                }
            }
        }

        StringBuilder sb = new StringBuilder(4096);
        // Fixed cols + dynamic item cols.
        sb.append("subjectLabel,eventOid,eventOrdinal,crfName,crfStatus,dateCompleted");
        for (String oid : allItemOids) {
            sb.append(',').append(csvCell(oid));
        }
        sb.append("\r\n");

        for (EventSnapshot es : snap.events()) {
            String evOid = (es.definition() == null) ? "SE_UNKNOWN" : es.definition().getOid();
            int evOrd = (es.definition() == null) ? 0 : es.definition().getOrdinal();
            for (CrfSnapshot cs : es.crfs()) {
                // Materialise item values by OID for this row.
                Map<String, String> rowVals = new HashMap<>();
                for (ItemSnapshot is : cs.items()) {
                    rowVals.put(itemOidFor(is),
                            is.data().getValue() == null ? "" : is.data().getValue());
                }
                String crfStatus = cs.eventCrf().getStatus() == null
                        ? "" : cs.eventCrf().getStatus().getName();
                String dateCompleted = cs.eventCrf().getDateCompleted() == null
                        ? "" : isoDate(cs.eventCrf().getDateCompleted());

                sb.append(csvCell(snap.studySubject().getLabel())).append(',');
                sb.append(csvCell(evOid)).append(',');
                sb.append(evOrd).append(',');
                sb.append(csvCell(cs.crfName())).append(',');
                sb.append(csvCell(crfStatus)).append(',');
                sb.append(csvCell(dateCompleted));
                for (String oid : allItemOids) {
                    sb.append(',').append(csvCell(rowVals.getOrDefault(oid, "")));
                }
                sb.append("\r\n");
            }
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /* =============================================================== */
    /* PDF renderer (com.lowagie / OpenPDF lineage)                    */
    /* =============================================================== */

    /**
     * Per-section per-event layout via the existing {@code com.lowagie.text}
     * API (DR-007 calls this out as the iText 2.1.2 → OpenPDF migration
     * path — both expose the same package). Layout:
     *
     * <ul>
     *   <li>Title block: study name + subject label.</li>
     *   <li>Identity block: secondary id, gender, DOB (year only,
     *       matches the SPA's add-subject form), enrolled-on.</li>
     *   <li>Per event: heading "{event-definition name} (ev OID)" +
     *       per-CRF sub-headings + a 3-column table (item, value,
     *       units). Audit-signature lines (if the CRF is signed) are
     *       italicised at the bottom of each form.</li>
     * </ul>
     */
    private byte[] renderPdf(CasebookSnapshot snap) throws DocumentException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48f, 48f, 48f, 48f);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        Font h3 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font italic = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8);

        // ---- Title + identity block ----
        Paragraph title = new Paragraph(snap.study().getName(), h1);
        title.setSpacingAfter(4f);
        doc.add(title);

        Paragraph subjectLine = new Paragraph(
                "Subject: " + snap.studySubject().getLabel(), h2);
        subjectLine.setSpacingAfter(10f);
        doc.add(subjectLine);

        PdfPTable identity = new PdfPTable(2);
        identity.setWidthPercentage(60f);
        identity.setHorizontalAlignment(Element.ALIGN_LEFT);
        addIdRow(identity, normal, "Secondary ID",
                snap.studySubject().getSecondaryLabel() == null
                        || snap.studySubject().getSecondaryLabel().isBlank()
                        ? "—" : snap.studySubject().getSecondaryLabel());
        addIdRow(identity, normal, "Gender", genderLabel(snap.subject()));
        addIdRow(identity, normal, "Year of birth", yobLabel(snap.subject()));
        addIdRow(identity, normal, "Enrolled on",
                snap.studySubject().getEnrollmentDate() == null
                        ? "—" : isoDate(snap.studySubject().getEnrollmentDate()));
        addIdRow(identity, normal, "Study OID", snap.study().getOid());
        addIdRow(identity, normal, "Generated", LocalDate.now().toString());
        doc.add(identity);
        doc.add(new Paragraph(" "));

        // ---- Events ----
        if (snap.events().isEmpty()) {
            doc.add(new Paragraph("No events scheduled for this subject.", normal));
        }
        for (EventSnapshot es : snap.events()) {
            String defName = es.definition() == null ? "(unknown event)" : es.definition().getName();
            String evOid = es.definition() == null ? "" : (" (" + es.definition().getOid() + ")");
            Paragraph evHead = new Paragraph(defName + evOid, h2);
            evHead.setSpacingBefore(8f);
            evHead.setSpacingAfter(4f);
            doc.add(evHead);

            String status = es.event().getSubjectEventStatus() == null
                    ? "(no status)" : es.event().getSubjectEventStatus().getName();
            String dates = "";
            if (es.event().getDateStarted() != null) dates += "Started " + isoDate(es.event().getDateStarted());
            if (es.event().getDateEnded() != null) {
                if (!dates.isEmpty()) dates += ", ";
                dates += "Ended " + isoDate(es.event().getDateEnded());
            }
            String meta = "Status: " + status + (dates.isEmpty() ? "" : " · " + dates);
            doc.add(new Paragraph(meta, small));

            if (es.crfs().isEmpty()) {
                doc.add(new Paragraph("No CRFs attached.", italic));
                continue;
            }

            for (CrfSnapshot cs : es.crfs()) {
                Paragraph crfHead = new Paragraph(cs.crfName(), h3);
                crfHead.setSpacingBefore(6f);
                crfHead.setSpacingAfter(2f);
                doc.add(crfHead);

                if (cs.items().isEmpty()) {
                    doc.add(new Paragraph("(no data entered)", italic));
                } else {
                    PdfPTable table = new PdfPTable(new float[] { 4f, 5f, 2f });
                    table.setWidthPercentage(100f);
                    addHeaderCell(table, "Item", h3);
                    addHeaderCell(table, "Value", h3);
                    addHeaderCell(table, "Units", h3);
                    for (ItemSnapshot is : cs.items()) {
                        String itemLabel = (is.item() == null) ? itemOidFor(is)
                                : firstNonBlank(is.item().getDescription(), is.item().getName(), itemOidFor(is));
                        String value = is.data().getValue() == null ? "" : is.data().getValue();
                        String units = (is.item() == null || is.item().getUnits() == null) ? "" : is.item().getUnits();
                        addBodyCell(table, itemLabel, normal);
                        addBodyCell(table, value, normal);
                        addBodyCell(table, units, normal);
                    }
                    doc.add(table);
                }

                // Signature line — only if validator_id and
                // date_validate_completed are present (matches the
                // SDV/sign convention from the legacy controllers).
                if (cs.eventCrf().getValidatorId() > 0
                        && cs.eventCrf().getDateValidateCompleted() != null) {
                    String sigLine = "Signed by user_id="
                            + cs.eventCrf().getValidatorId()
                            + " on " + isoDate(cs.eventCrf().getDateValidateCompleted());
                    doc.add(new Paragraph(sigLine, italic));
                } else if (cs.eventCrf().getDateCompleted() != null) {
                    doc.add(new Paragraph("Data entry completed on "
                            + isoDate(cs.eventCrf().getDateCompleted()), italic));
                }
            }
        }

        doc.close();
        return baos.toByteArray();
    }

    private static void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(new java.awt.Color(240, 240, 240));
        cell.setPadding(4f);
        table.addCell(cell);
    }

    private static void addBodyCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setPadding(4f);
        table.addCell(cell);
    }

    private static void addIdRow(PdfPTable table, Font font, String label, String value) {
        PdfPCell k = new PdfPCell(new Phrase(label, font));
        k.setPadding(3f);
        k.setBorderWidth(0.25f);
        PdfPCell v = new PdfPCell(new Phrase(value == null ? "" : value, font));
        v.setPadding(3f);
        v.setBorderWidth(0.25f);
        table.addCell(k);
        table.addCell(v);
    }

    /* =============================================================== */
    /* Audit                                                            */
    /* =============================================================== */

    /**
     * One {@code audit_log_event} row per successful download. The
     * {@code new_value} column carries the selected format so the
     * Audit Trail SPA can render "{user} exported {subject} (csv)"
     * without an extra schema field.
     *
     * <p>Failures here log at WARN but never roll back the download —
     * matches the {@link MeApiController#emitProfileAudit} pattern:
     * losing one audit row is annoying, refusing to ship the
     * already-rendered data is worse.
     */
    private void emitExportAudit(int userId, int studySubjectId, String label, String format) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'study_subject', ?, ?, ?, ?)")) {
            ps.setInt(1, AUDIT_TYPE_SUBJECT_DATA_EXPORTED);
            ps.setInt(2, userId);
            ps.setInt(3, studySubjectId);
            ps.setString(4, label == null ? "" : label);
            ps.setString(5, "");
            ps.setString(6, format);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to write subject-export audit row for study_subject_id={} format={}: {}",
                    studySubjectId, format, e.getMessage());
        }
    }

    /* =============================================================== */
    /* Helpers                                                          */
    /* =============================================================== */

    private static String itemOidFor(ItemSnapshot is) {
        if (is.item() != null && is.item().getOid() != null && !is.item().getOid().isBlank()) {
            return is.item().getOid();
        }
        return "I_" + is.data().getItemId();
    }

    private static String genderLabel(SubjectBean subj) {
        if (subj == null) return "—";
        return switch (Character.toLowerCase(subj.getGender())) {
            case 'f' -> "F";
            case 'm' -> "M";
            case 'o' -> "O";
            case 'u' -> "U";
            default -> "—";
        };
    }

    private static String yobLabel(SubjectBean subj) {
        if (subj == null || subj.getDateOfBirth() == null) return "—";
        // sql.Date#toInstant throws; route through epoch ms.
        return String.valueOf(java.time.Instant.ofEpochMilli(subj.getDateOfBirth().getTime())
                .atZone(ZoneId.systemDefault()).getYear());
    }

    private static String isoDate(Date d) {
        if (d == null) return "";
        return LocalDate.ofInstant(
                java.time.Instant.ofEpochMilli(d.getTime()), ZoneId.systemDefault()).toString();
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return "";
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return "";
    }

    /**
     * Escape characters for XML attribute / text content. Covers the
     * five mandatory entity replacements per the XML 1.0 spec; works
     * for both elements and attributes (the broader rule).
     */
    private static String escAttr(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    // Filter ASCII control chars that aren't permitted in XML 1.0.
                    if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') {
                        sb.append('?');
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * CSV cell quoting per RFC 4180: wrap in double quotes if the
     * cell contains a comma, double-quote, CR or LF; escape internal
     * double quotes by doubling.
     */
    private static String csvCell(String s) {
        if (s == null) return "";
        boolean needsQuoting = s.indexOf(',') >= 0
                || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0;
        if (!needsQuoting) return s;
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') sb.append("\"\"");
            else sb.append(ch);
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Strip characters disallowed in filenames across the common OSes
     * and replace whitespace with underscores. Subject labels in
     * production are typically alphanumeric (validated by
     * {@link SubjectsApiController}); this is a defensive net for the
     * malformed-data case.
     */
    private static String sanitizeFilename(String s) {
        if (s == null || s.isBlank()) return "subject";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
                sb.append(ch);
            } else if (Character.isWhitespace(ch)) {
                sb.append('_');
            }
            // drop anything else
        }
        if (sb.length() == 0) return "subject";
        return sb.toString();
    }
}
