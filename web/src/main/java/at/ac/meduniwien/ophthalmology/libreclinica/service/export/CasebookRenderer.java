/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.export;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.sql.DataSource;

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
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.extract.FileItemValue;

/**
 * One subject's whole record, and the two text renderings of it.
 *
 * <p>P3.8 lifted this out of {@code SubjectExportApiController}, where it had
 * grown as the per-subject export's private machinery. The dataset bundle
 * needs the same casebook for every subject in a dataset, and the subject
 * export and the dataset export producing two different casebooks for the
 * same person would be a defect nobody would notice until a recipient
 * compared them. One walk, one ODM, one CSV.
 *
 * <p>Everything here is static and takes its {@link DataSource} explicitly:
 * it runs on the request thread for a subject export and on the Quartz job
 * thread for a dataset export, and neither has a Spring context to lean on.
 */
public final class CasebookRenderer {

    private CasebookRenderer() {}

    /** The namespace for annotations CDISC ODM has no element for. */
    public static final String MUW_ODM_NS = "http://www.meduniwien.ac.at/ns/odm_ext/v1";

    /* =============================================================== */
    /* The snapshot                                                    */
    /* =============================================================== */

    /**
     * In-memory snapshot of a single subject's whole data — built once by
     * {@link #collect} and consumed by every renderer.
     *
     * <p>{@code events} is in protocol order (definition ordinal); each
     * event's {@code crfs} is in event_crf id order; each CRF's {@code items}
     * is in the order returned by {@link ItemDataDAO#findAllByEventCRFId} —
     * which mirrors the order the data was entered.
     */
    public record CasebookSnapshot(
            StudySubjectBean studySubject,
            SubjectBean subject,
            StudyBean study,
            List<EventSnapshot> events) {}

    public record EventSnapshot(
            StudyEventBean event,
            StudyEventDefinitionBean definition,
            List<CrfSnapshot> crfs) {}

    public record CrfSnapshot(
            EventCRFBean eventCrf,
            CRFVersionBean crfVersion,
            String crfName,
            List<ItemSnapshot> items) {}

    public record ItemSnapshot(
            ItemDataBean data,
            ItemBean item) {}

    /**
     * Walk the casebook: every event, every event_crf, every item_data. The
     * walk produces a uniform shape every format renders from, keeping the
     * per-format branches independent of DAO call sequencing.
     */
    public static CasebookSnapshot collect(DataSource dataSource, StudySubjectBean ss,
                                           SubjectBean subj, StudyBean study) {
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

    /** The plain ODM: no manifest, so no {@code muw:ManifestPath}. */
    public static byte[] renderOdm(CasebookSnapshot snap) {
        return renderOdm(snap, Map.of());
    }

    /**
     * Build a minimal ODM 1.3 XML document with one {@code SubjectData}
     * block. Hand-built rather than routed through the legacy
     * {@code OdmDataCollector} / {@code ClinicalDataUnit} pipeline because
     * that pipeline is dataset-driven (requires a persisted dataset row).
     *
     * <p>ItemGroup is collapsed to a single per-CRF group ({@code IG_} + CRF
     * version OID) because the SPA-side seed data doesn't track grouping per
     * item. Downstream consumers that require strict group fidelity should
     * use the legacy dataset-driven export.
     *
     * @param acquisitionPaths where each ingested file will sit in the bundle,
     *     for the {@code muw:ManifestPath} annotation. Empty outside a bundle.
     */
    public static byte[] renderOdm(CasebookSnapshot snap, Map<Long, String> acquisitionPaths) {
        StringBuilder sb = new StringBuilder(8192);
        String createdAt = java.time.OffsetDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\"")
          .append(" xmlns:OpenClinica=\"http://www.openclinica.org/ns/odm_ext_v130/v3.1\"")
          // P3.7 — an ItemData nobody typed carries where it came from. Without
          // this a reader of the casebook cannot tell an auto-populated value
          // from a clinician's, nor find the file that produced it.
          .append(" xmlns:muw=\"").append(MUW_ODM_NS).append("\"")
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
                    String value = exportValue(is);
                    sb.append("            <ItemData ItemOID=\"").append(escAttr(itemOid))
                      .append("\" Value=\"").append(escAttr(value)).append("\"")
                      .append(provenanceAttrs(is, acquisitionPaths))
                      .append("/>\n");
                }

                sb.append("          </ItemGroupData>\n");
                sb.append("        </FormData>\n");
            }

            sb.append("      </StudyEventData>\n");
        }

        sb.append("    </SubjectData>\n");
        sb.append("  </ClinicalData>\n");
        sb.append("</ODM>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Where a value came from, when it was not a person.
     *
     * <p>P3.7 — a CRF value written by the platform looks identical to one a
     * clinician typed once it is in an ODM file, and a reader auditing the
     * export has no way to tell them apart or to find the evidence. These
     * attributes carry {@code source_kind} and the id of whatever produced it,
     * and for a file-backed source the path it occupies in the bundle, so a
     * value and its evidence travel together.
     *
     * <p>Empty for a value a person typed, which is the common case and needs
     * no annotation.
     *
     * <p>{@code muw:ManifestPath} appears only in a bundle, and only for a file
     * this bundle actually carries — {@code acquisitionPaths} is empty for the
     * plain ODM export, where there is no manifest for a path to point into.
     * An inference-derived value carries its job id instead; the manifest's
     * {@code inference[]} entries name the same id, which is the join, and the
     * job's artifacts are a directory rather than one file.
     */
    static String provenanceAttrs(ItemSnapshot is, Map<Long, String> acquisitionPaths) {
        String sourceKind = is.data().getSourceKind();
        if (sourceKind == null || sourceKind.isBlank()) return "";
        StringBuilder sb = new StringBuilder(64);
        sb.append(" muw:SourceKind=\"").append(escAttr(sourceKind)).append("\"");
        Long ingestItemId = is.data().getSourceIngestItemId();
        if (ingestItemId != null) {
            sb.append(" muw:IngestItemId=\"").append(ingestItemId).append("\"");
            String path = acquisitionPaths.get(ingestItemId);
            if (path != null) {
                // So a reader can go from a CRF value straight to the file that
                // justifies it, without parsing the manifest.
                sb.append(" muw:ManifestPath=\"").append(escAttr(path)).append("\"");
            }
        }
        Long retinalJobId = is.data().getSourceRetinalJobId();
        if (retinalJobId != null) {
            sb.append(" muw:RetinalJobId=\"").append(retinalJobId).append("\"");
        }
        return sb.toString();
    }

    /**
     * The value to export for an item, with a FILE item's server path reduced
     * to its filename.
     *
     * <p>The rule itself lives in {@link FileItemValue} because the same leak
     * had a second route — the dataset extract — and fixing one without the
     * other would have left the disclosure in place for anyone who exports a
     * dataset rather than a subject.
     */
    public static String exportValue(ItemSnapshot is) {
        String raw = is.data().getValue() == null ? "" : is.data().getValue();
        int typeId = (is.item() == null) ? 0 : is.item().getItemDataTypeId();
        return FileItemValue.forExport(raw, typeId);
    }

    /* =============================================================== */
    /* CSV renderer                                                    */
    /* =============================================================== */

    /**
     * One row per event-CRF, columns = item OIDs encountered in that subject's
     * data, values = item_data value text. Handy for spreadsheet open-and-look.
     */
    public static byte[] renderCsv(CasebookSnapshot snap) {
        // Collect all item OIDs in this subject's data first so columns
        // line up across rows. Sort alphabetically for deterministic output.
        TreeSet<String> allItemOids = new TreeSet<>();
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
                    rowVals.put(itemOidFor(is), exportValue(is));
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
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /* =============================================================== */
    /* Helpers shared with the PDF renderer                            */
    /* =============================================================== */

    public static String itemOidFor(ItemSnapshot is) {
        if (is.item() != null && is.item().getOid() != null && !is.item().getOid().isBlank()) {
            return is.item().getOid();
        }
        return "I_" + is.data().getItemId();
    }

    public static String genderLabel(SubjectBean subj) {
        if (subj == null) return "—";
        return switch (Character.toLowerCase(subj.getGender())) {
            case 'f' -> "F";
            case 'm' -> "M";
            case 'o' -> "O";
            case 'u' -> "U";
            default -> "—";
        };
    }

    public static String yobLabel(SubjectBean subj) {
        if (subj == null || subj.getDateOfBirth() == null) return "—";
        // sql.Date#toInstant throws; route through epoch ms.
        return String.valueOf(java.time.Instant.ofEpochMilli(subj.getDateOfBirth().getTime())
                .atZone(ZoneId.systemDefault()).getYear());
    }

    public static String isoDate(Date d) {
        if (d == null) return "";
        return LocalDate.ofInstant(
                java.time.Instant.ofEpochMilli(d.getTime()), ZoneId.systemDefault()).toString();
    }

    public static String firstNonBlank(String... candidates) {
        if (candidates == null) return "";
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return "";
    }

    /**
     * Escape characters for XML attribute / text content. Covers the five
     * mandatory entity replacements per the XML 1.0 spec; works for both
     * elements and attributes (the broader rule).
     */
    public static String escAttr(String s) {
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
     * CSV cell quoting per RFC 4180: wrap in double quotes if the cell
     * contains a comma, double-quote, CR or LF; escape internal double quotes
     * by doubling.
     */
    public static String csvCell(String s) {
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
}
