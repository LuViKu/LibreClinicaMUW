# Long-running studies — operator notes

Operational guidance for institutional teams running observational
or longitudinal studies on LibreClinica MUW Ophthalmology where
subjects accumulate **10–20+ visits over multiple years** (e.g. the
MUW geographic-atrophy cohort, 6-month follow-up over 5–10 years).

The platform itself supports the design without code changes — see
[GA-cohort feasibility probe in known-issues.md §2](modernization/phase-e/known-issues.md#2-long-running-studies--subject-matrix--per-subject-event-view-at-20-visits)
and the `StudyEventScheduleIT.testRepeatingEventScalesTo15Visits`
regression test. This document covers the **operator-facing**
concerns that grow with study duration: audit-trail volume,
PostgreSQL maintenance, and backup ergonomics.

## Audit-trail growth model

Every clinically meaningful change is captured in `audit_log_event`:
- Item-data edits (`item_data` table; one row per CRF field edit
  + revision)
- Event status transitions (SCHEDULED → DATA_ENTRY_STARTED →
  COMPLETED → LOCKED → SIGNED)
- Discrepancy-note resolutions
- Signature events (one row per Subject sign-off)

### Rough volume estimate per subject

Assumptions: one Visit CRF with ~50 items, 50 % of items edited
twice on average (correction + sign-off cycle), one signature event
per visit, one status transition per visit, no discrepancies.

| Per-visit event | Audit rows |
|---|---|
| Item-data edits | 50 items × 1.5 edits ≈ **75** |
| Event status transitions (SCHEDULED → COMPLETED → SIGNED) | **3** |
| Signature event | **1** |
| Total per visit | **~80** |

For a GA-cohort subject with 20 visits: **~1 600 audit rows over the
subject's study lifetime**. A 200-subject cohort: **~320 000 audit
rows total**.

For a single-site institutional deployment with, say, three concurrent
long-running studies of similar size: **~1 M audit rows** over the
multi-year cohort lifetime. This is comfortable for vanilla PostgreSQL
on commodity hardware — query latency for the audit screens
(StudyAuditLog, AuditUserActivity) stays sub-second with the existing
indexes through several million rows.

### When audit_log_event growth becomes a concern

PostgreSQL planner + index maintenance behaviour starts to show stress
in three rough zones:

| Row count | Practical impact | Operator action |
|---|---|---|
| **< 1 M** | None | Default `autovacuum` is sufficient. No action. |
| **1 M – 10 M** | Query latency for full-table audit reports may climb to several seconds. Sequential scans on un-indexed predicates surface. | (a) Verify index coverage matches your most-frequent audit query predicates. (b) Tune `autovacuum_analyze_scale_factor` down to `0.02` so statistics stay fresh as the table grows. |
| **10 M – 100 M** | Index bloat after long write-heavy windows; backup time grows linearly. | Schedule `REINDEX audit_log_event_pkey` quarterly. Consider partitioning (next section). |
| **> 100 M** | Single-table operations are slow; backups span hours. | **Required**: declarative range partitioning by `audit_date` (per year or per quarter). Each partition stays small enough to vacuum/REINDEX independently. |

### Partitioning strategy (when you cross 10 M rows)

PostgreSQL declarative partitioning works cleanly on
`audit_log_event` because every row has a date (`audit_date`
column, indexed) and the table is append-mostly. Phased
migration:

1. Stop writes (short maintenance window — typically < 5 min).
2. Rename `audit_log_event` to `audit_log_event_legacy`.
3. Create new partitioned table with the same schema
   (`PARTITION BY RANGE (audit_date)`).
4. Create year-range partitions covering the full date span of
   the legacy table + the next year.
5. `INSERT INTO audit_log_event SELECT * FROM audit_log_event_legacy;`
   (the planner routes each row to the correct partition).
6. Drop the legacy table.
7. Re-enable writes.

Future-year partitions are created via a quarterly cron job:
```sql
CREATE TABLE audit_log_event_2027
  PARTITION OF audit_log_event
  FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
```

Implementation is out-of-band for the application (no LibreClinica
code change required); the operator runs a one-time SQL migration
plus a tiny cron job.

## PostgreSQL maintenance cadence

For studies running multi-year:

| Cadence | Action |
|---|---|
| Weekly | `VACUUM ANALYZE` on `audit_log_event`, `item_data`, `study_event`. `autovacuum` covers most cases; the explicit weekly call catches any starvation. |
| Quarterly | `REINDEX TABLE audit_log_event` once row count crosses 5 M (otherwise index bloat masks query plans). |
| Annually | Review query plans for the Study Audit Log + Audit User Activity screens against current row counts. Adjust indexes if a previously-fast predicate now seq-scans. |

The compose stack and CI defaults do nothing of this — the institutional
ops team owns it.

## Backup strategy

`pg_dump` time scales roughly linearly with row count. For a
multi-year cohort:

| Total DB row count | Approx. `pg_dump --format=custom` time | Approx. dump size |
|---|---|---|
| < 1 M | < 1 min | tens of MB |
| 1 M – 10 M | 1–10 min | 100s of MB |
| > 10 M | 10–60 min | several GB |

Past 10 M rows, switch from nightly full dumps to:
- Weekly full `pg_dump`
- Daily incremental WAL archiving (`archive_command`)
- Point-in-time-recovery (PITR) via `pg_basebackup`

This is standard PostgreSQL operator practice; not LibreClinica-
specific. The compose stack ships with neither — the operator
configures it at deployment time.

## Visit-count limits (none in code or schema)

For completeness — there is **no upper bound** on visits per
subject anywhere in the platform:

- `study_event.sample_ordinal` is PostgreSQL `int4` (≈ 2 billion).
- The unique constraint
  `(study_event_definition_id, study_subject_id, sample_ordinal)`
  enforces ordinal-distinctness but not count.
- The `ScheduleStudyEvent` servlet auto-increments ordinal; no
  cap.
- `ViewStudySubject` paginates the visit list at 10/page (the
  legacy "event browser list" — `ebl_page` URL param);
  arbitrary visit counts paginate cleanly.
- The Subject Matrix (`/ListStudySubjects` + `/FindSubjectsData`)
  aggregates events per (subject, SED) into `{count, statusName}`
  — render cost is independent of visit count per subject.

The GA-cohort feasibility probe (2026-05-31) seeded one subject
with 20 events spanning 9 years and confirmed both surfaces render
in under 500 ms TTFB on stock compose hardware.

## When to revisit this document

- Cohort size crosses 500 subjects.
- A single subject crosses 50 visits (no platform limit, but the
  UX of paginating 5 pages of visits warrants a Phase E SPA review).
- `audit_log_event` row count crosses 1 M (autovacuum tuning hint).
- The MUW Ophthalmology team picks up a second long-running cohort
  in parallel (compound growth on the same database).

## Reference

- [Phase E known issues §2 — Subject Matrix scalability finding](modernization/phase-e/known-issues.md)
- [StudyEventScheduleIT regression net](../../core/src/test/java/at/ac/meduniwien/ophthalmology/libreclinica/it/StudyEventScheduleIT.java)
- PostgreSQL declarative-partitioning docs: https://www.postgresql.org/docs/14/ddl-partitioning.html
