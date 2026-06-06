/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.MigrateVersionResult;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.MigrateVersionResult.SedMigrationRow;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.VersionUsageReport;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.VersionUsageReport.EventDefinitionReference;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase E.6 {@code crf-library} cluster — orchestration helpers for
 * version-level lifecycle operations that need cross-table reasoning.
 *
 * <p>Three responsibilities:
 * <ol>
 *   <li>{@link #computeUsageReport} — assemble the references that
 *       block a hard-remove of a CRF version (used by the DELETE
 *       endpoint and by the migrate dialog's "what would break"
 *       summary).</li>
 *   <li>{@link #planMigration} / {@link #commitMigration} — for the
 *       batch v.A → v.B migration: walks every event_definition_crf
 *       that currently defaults to {@code from}, intersects with the
 *       caller's filter list, and either returns the plan (dry-run) or
 *       writes the new {@code default_version_id} + an audit row per
 *       affected SED (commit).</li>
 * </ol>
 *
 * <p><b>Audit-strategy note (reviewer flag AC6 resolution)</b>: the
 * migration writes {@code default_version_id}, not {@code status_id}.
 * The legacy {@code writeLifecycleAudit} helper in
 * {@code CrfsApiController} only models status_id transitions; we
 * therefore expose a dedicated {@link #writeMigrationAudit} that emits
 * one {@code audit_event} row per migrated SED with column_name set to
 * {@code default_version_id} and old/new values carrying the CRF
 * version OIDs (operator-readable; the legacy schema accepts strings).
 */
@Service
public class CrfVersionMigrationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(CrfVersionMigrationService.class);

    private final DataSource dataSource;

    @Autowired
    public CrfVersionMigrationService(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ------------------------------------------------------------------ */
    /* VersionUsageReport — references that block hard-remove              */
    /* ------------------------------------------------------------------ */

    /**
     * Walk every {@code event_definition_crf} row tied to the doomed
     * version's parent CRF + every {@code event_crf} row that has data
     * against the doomed version, assembling a structured
     * {@link VersionUsageReport}. Empty {@code blockingEventDefinitions}
     * + zero {@code eventCrfCount} means the version is safe to drop.
     *
     * <p>Subject labels are capped at {@value #SUBJECT_SAMPLE_CAP}; the
     * SPA renders the cap + an ellipsis when truncated.
     */
    private static final int SUBJECT_SAMPLE_CAP = 5;

    public VersionUsageReport computeUsageReport(CRFBean crf, CRFVersionBean version) {
        EventDefinitionCRFDAO edcDao = new EventDefinitionCRFDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);
        EventCRFDAO ecDao = new EventCRFDAO(dataSource);

        List<EventDefinitionReference> blocking = new ArrayList<>();
        for (EventDefinitionCRFBean edc : edcDao.findByDefaultVersion(version.getId())) {
            StudyEventDefinitionBean sed = (StudyEventDefinitionBean)
                    sedDao.findByPK(edc.getStudyEventDefinitionId());
            if (sed == null || sed.getId() == 0) continue;
            StudyBean parentStudy = (StudyBean) studyDao.findByPK(edc.getStudyId());
            blocking.add(new EventDefinitionReference(
                    parentStudy != null ? parentStudy.getOid() : null,
                    sed.getOid(),
                    sed.getName()));
        }

        // event_crf rows tied to this version — count + sample subject
        // labels (label = study_subject.label or its OID, whichever is
        // populated). We use the existing findAllByCRFVersion which
        // returns EventCRFBean lacking direct subject label; we keep
        // the report best-effort and surface only the count when label
        // hydration would need extra DAO hops.
        var eventCrfs = ecDao.findAllByCRFVersion(version.getId());
        int eventCrfCount = eventCrfs.size();
        List<String> sampleLabels = new ArrayList<>();
        for (int i = 0; i < eventCrfs.size() && sampleLabels.size() < SUBJECT_SAMPLE_CAP; i++) {
            // EventCRFBean carries study_subject_id only; surfacing the
            // numeric ID is operationally useless for the operator,
            // and the StudySubjectDAO lookup is heavy. We expose
            // "studySubject#<id>" as a stable handle the operator can
            // grep against in the legacy UI; the count above is the
            // primary blocker indicator.
            sampleLabels.add("studySubject#" + eventCrfs.get(i).getStudySubjectId());
        }

        return new VersionUsageReport(
                crf.getOid(),
                version.getOid(),
                version.getName(),
                blocking,
                eventCrfCount,
                sampleLabels);
    }

    /* ------------------------------------------------------------------ */
    /* Migration plan + commit                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Compute the per-SED migration plan without writing. {@code sedOids}
     * may be {@code null}/empty to mean "every event definition that
     * currently defaults to {@code fromVersion}".
     */
    public MigrateVersionResult planMigration(CRFBean crf,
                                              CRFVersionBean fromVersion,
                                              CRFVersionBean toVersion,
                                              List<String> sedOidFilter) {
        return runMigration(crf, fromVersion, toVersion, sedOidFilter, null, true);
    }

    /**
     * Apply the migration: re-point every selected SED's
     * {@code default_version_id} + write one audit row per affected SED.
     */
    public MigrateVersionResult commitMigration(CRFBean crf,
                                                CRFVersionBean fromVersion,
                                                CRFVersionBean toVersion,
                                                List<String> sedOidFilter,
                                                UserAccountBean actor) {
        return runMigration(crf, fromVersion, toVersion, sedOidFilter, actor, false);
    }

    private MigrateVersionResult runMigration(CRFBean crf,
                                              CRFVersionBean fromVersion,
                                              CRFVersionBean toVersion,
                                              List<String> sedOidFilter,
                                              UserAccountBean actor,
                                              boolean dryRun) {
        EventDefinitionCRFDAO edcDao = new EventDefinitionCRFDAO(dataSource);
        StudyEventDefinitionDAO sedDao = new StudyEventDefinitionDAO(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);

        Set<String> filter = sedOidFilter == null
                ? Collections.emptySet()
                : new HashSet<>(sedOidFilter);

        List<SedMigrationRow> rows = new ArrayList<>();
        int migratedCount = 0;

        for (EventDefinitionCRFBean edc : edcDao.findByDefaultVersion(fromVersion.getId())) {
            StudyEventDefinitionBean sed = (StudyEventDefinitionBean)
                    sedDao.findByPK(edc.getStudyEventDefinitionId());
            if (sed == null || sed.getId() == 0) continue;
            StudyBean parentStudy = (StudyBean) studyDao.findByPK(edc.getStudyId());
            String studyOid = parentStudy != null ? parentStudy.getOid() : null;

            boolean inFilter = filter.isEmpty() || filter.contains(sed.getOid());
            if (!inFilter) {
                rows.add(new SedMigrationRow(studyOid, sed.getOid(), sed.getName(),
                        false, "not in selected SED filter"));
                continue;
            }

            if (!dryRun) {
                edc.setDefaultVersionId(toVersion.getId());
                edc.setUpdater(actor);
                edc.setUpdatedDate(new Date());
                edcDao.update(edc);
                writeMigrationAudit(actor, edc, sed, fromVersion, toVersion);
            }
            rows.add(new SedMigrationRow(studyOid, sed.getOid(), sed.getName(),
                    true, null));
            migratedCount++;
        }

        // Echo back SEDs the operator named in the filter but which
        // never defaulted to fromVersion in the first place (e.g. the
        // operator picked the wrong CRF's SED list). They appear as
        // migrated:false with a useful skip reason instead of being
        // silently dropped from the result.
        if (!filter.isEmpty()) {
            Set<String> seenOids = new HashSet<>();
            for (SedMigrationRow row : rows) seenOids.add(row.sedOid());
            for (String requested : filter) {
                if (!seenOids.contains(requested)) {
                    rows.add(new SedMigrationRow(null, requested, null,
                            false,
                            "SED does not currently default to version '" + fromVersion.getOid() + "'"));
                }
            }
        }

        return new MigrateVersionResult(
                crf.getOid(),
                fromVersion.getOid(),
                toVersion.getOid(),
                dryRun,
                migratedCount,
                rows);
    }

    /**
     * Emit one {@code audit_event} row recording the
     * {@code default_version_id} flip on a single
     * {@code event_definition_crf} row.
     *
     * <p>Mirrors the legacy {@code writeLifecycleAudit} shape but uses
     * {@code column_name = "default_version_id"} and stores the CRF
     * version OIDs (not numeric IDs) in old/new value so the audit row
     * is human-readable when the underlying numeric IDs change across
     * deployments (e.g. data-migration re-numbering).
     */
    private void writeMigrationAudit(UserAccountBean actor,
                                     EventDefinitionCRFBean edc,
                                     StudyEventDefinitionBean sed,
                                     CRFVersionBean fromVersion,
                                     CRFVersionBean toVersion) {
        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(actor.getId());
            ae.setAuditTable("event_definition_crf");
            ae.setEntityId(edc.getId());
            ae.setColumnName("default_version_id");
            ae.setOldValue(fromVersion.getOid());
            ae.setNewValue(toVersion.getOid());
            ae.setActionMessage("crf_version_migrate: SED " + sed.getOid()
                    + " (" + fromVersion.getOid() + " → " + toVersion.getOid()
                    + ") by " + actor.getName());
            new AuditEventDAO(dataSource).create(ae);
        } catch (Exception e) {
            // Audit failures never abort the migration — the actual
            // mutation already landed; we surface the audit slip in the
            // log so an operator can reconcile manually.
            LOG.warn("Audit write failed for migration edc#{} sed={} (continuing): {}",
                    edc.getId(), sed.getOid(), e.getMessage());
        }
    }
}
