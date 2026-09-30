/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Wire shapes of {@code /api/v1/crfs/{crfOid}/event-crf-migration}: moving
 * existing event CRFs to another version of their CRF. Not to be confused
 * with {@code POST /crfs/{oid}/versions/{from}/migrate-to/{to}}, which only
 * changes the version that new event CRFs get.
 */
public final class EventCrfMigrationDto {

    private EventCrfMigrationDto() {
    }

    /**
     * What to move. Empty {@code siteOids} means the study itself and all its
     * available sites; empty {@code eventDefinitionOids} all its available
     * event definitions (legacy {@code BatchCRFMigrationController}
     * defaults). {@code studySubjectLabel} and {@code eventCrfIds} narrow the
     * selection down to one subject or to single event CRFs.
     * {@code expectedEventCrfCount} is required by the run: the count the
     * preview showed, so a selection that changed in between is refused
     * instead of moved.
     */
    @Schema(name = "EventCrfMigrationRequest")
    public record Request(
            String studyOid,
            String sourceVersionOid,
            String targetVersionOid,
            List<String> siteOids,
            List<String> eventDefinitionOids,
            String studySubjectLabel,
            List<Integer> eventCrfIds,
            Integer expectedEventCrfCount
    ) {}

    @Schema(name = "EventCrfMigrationRef")
    public record Ref(String oid, String name) {}

    /** A version of the CRF, with how many event CRFs of the study are on it. */
    @Schema(name = "EventCrfMigrationVersionOption")
    public record VersionOption(String oid, String name, String status, int eventCrfCount) {}

    /** What the screen offers for one study: its versions, sites and event definitions. */
    @Schema(name = "EventCrfMigrationOptions")
    public record Options(
            String crfOid,
            String crfName,
            Ref study,
            List<VersionOption> versions,
            List<Ref> sites,
            List<Ref> eventDefinitions
    ) {}

    /**
     * One event CRF. In a preview the flags say what the move will clear; in
     * a result's log, what it cleared.
     */
    @Schema(name = "EventCrfMigrationRow")
    public record Row(
            int eventCrfId,
            String studySubjectLabel,
            String siteOid,
            String siteName,
            String eventDefinitionOid,
            String eventName,
            int eventOrdinal,
            boolean sdvVerified,
            boolean subjectSigned,
            boolean eventSigned,
            boolean eventCrfSigned
    ) {}

    /** An event CRF the selection matched but that is not moved, and why. */
    @Schema(name = "EventCrfMigrationSkippedRow")
    public record Skipped(Row row, String reason) {}

    /**
     * Event CRFs not moved because the site's event definition does not offer
     * both versions (its {@code selected_version_ids}).
     */
    @Schema(name = "EventCrfMigrationNotOffered")
    public record NotOffered(String siteOid, String siteName, String eventDefinitionOid,
                             String eventName, int eventCrfCount) {}

    /** An item with entered values that the target version does not contain. */
    @Schema(name = "EventCrfMigrationHiddenItem")
    public record HiddenItem(String name, String oid, int valueCount) {}

    @Schema(name = "EventCrfMigrationPreview")
    public record Preview(
            String crfOid,
            String crfName,
            Ref study,
            Ref sourceVersion,
            Ref targetVersion,
            List<Ref> sites,
            List<Ref> eventDefinitions,
            String studySubjectLabel,
            int eventCrfCount,
            int subjectCount,
            int sdvVerifiedCount,
            int signedSubjectCount,
            int signedEventCount,
            int signedEventCrfCount,
            List<Row> eventCrfs,
            boolean eventCrfsTruncated,
            List<Skipped> locked,
            List<NotOffered> notOffered,
            int hiddenValueCount,
            List<HiddenItem> hiddenItems
    ) {}

    @Schema(name = "EventCrfMigrationResult")
    public record Result(
            String crfOid,
            String crfName,
            Ref study,
            Ref sourceVersion,
            Ref targetVersion,
            int migratedEventCrfCount,
            int subjectCount,
            int sdvClearedCount,
            int unsignedSubjectCount,
            int unsignedEventCount,
            int unsignedEventCrfCount,
            List<Row> log,
            String completedAt
    ) {}
}
