/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.NewCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.SpreadSheetTableClassic;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.SpreadSheetTableRepeating;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.CRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.MeasurementUnitDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.CRFReadingException;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Phase E A8.3 follow-up — stateless CRF spreadsheet parser adapter.
 *
 * <p>Wraps the legacy {@link SpreadSheetTableRepeating} +
 * {@link SpreadSheetTableClassic} parsers so the REST upload endpoint
 * can invoke them without the servlet's session / page-message
 * scaffolding. The parsers themselves are session-free; the legacy
 * {@code CreateCRFVersionServlet} only used the session to stash
 * preview state (which the REST flow doesn't need — the SPA shows
 * confirmation via the response body).
 *
 * <p>Discriminator: {@code SpreadSheetTableRepeating} detects whether
 * the workbook has a {@code "groups"} sheet during construction. If
 * yes, it parses as a repeating template; if no, we re-open the
 * stream and parse via {@code SpreadSheetTableClassic}.
 *
 * <p>Locale handling: the parser uses {@link ResourceBundle} for
 * error-message i18n. We bind the resource bundle on the current
 * thread before parsing so {@code Term.getName()} and friends resolve
 * correctly even though the request didn't come through the legacy
 * servlet filter chain.
 *
 * <p><b>Persistence</b>: the parser's {@code NewCRFBean.insertToDB()}
 * writes the {@code crf_version} row + its items + sections directly
 * via JDBC queries (legacy convention, not Hibernate). The REST
 * controller does NOT pre-create a {@code crf_version} row — this
 * service owns the version persistence end-to-end so the parsed
 * spreadsheet drives the row's content.
 */
@Service
public class CrfSpreadsheetParserService {

    private static final Logger LOG = LoggerFactory.getLogger(CrfSpreadsheetParserService.class);

    private final DataSource dataSource;
    private final MeasurementUnitDao measurementUnitDao;

    @Autowired
    public CrfSpreadsheetParserService(@Qualifier("dataSource") DataSource dataSource,
                                       MeasurementUnitDao measurementUnitDao) {
        this.dataSource = dataSource;
        this.measurementUnitDao = measurementUnitDao;
    }

    /**
     * Parse + persist a CRF version from a stored spreadsheet file.
     *
     * @param storedFile the on-disk file the upload endpoint already
     *                   persisted to {@code <filePath>/crf/original/}
     * @param crf the parent CRF the new version belongs to (already
     *            resolved by the controller from the OID path
     *            variable)
     * @param versionName the new version's name (typically a short
     *                    identifier like {@code v1.0}) — the parser
     *                    uses this when constructing the resulting
     *                    {@code crf_version} row
     * @param uploader the {@link UserAccountBean} performing the
     *                 upload (becomes the row's owner)
     * @param studyId the active study id (kept for legacy parity —
     *                the parsers don't currently use it for anything
     *                except internal logging)
     * @param locale the request locale; defaults to English when null
     * @return a {@link Result} carrying either the persisted
     *         {@link CRFVersionBean} on success or the collected
     *         parse-time errors on failure
     */
    public Result parseAndPersist(Path storedFile,
                                  CRFBean crf,
                                  String versionName,
                                  UserAccountBean uploader,
                                  int studyId,
                                  Locale locale) {
        Locale effectiveLocale = locale != null ? locale : Locale.ENGLISH;
        ResourceBundleProvider.updateLocale(effectiveLocale);
        ResourceBundle respage = ResourceBundleProvider.getPageMessagesBundle(effectiveLocale);

        NewCRFBean parsed;
        try {
            parsed = parse(storedFile, crf, versionName, uploader, studyId,
                    effectiveLocale, respage);
        } catch (CRFReadingException cre) {
            LOG.warn("CRF parse failed (CRFReadingException) for crfOid={} versionName={}: {}",
                    crf.getOid(), versionName, cre.getMessage());
            return Result.failure(List.of("Parse error: " + cre.getMessage()));
        } catch (IOException ioe) {
            LOG.warn("CRF parse failed (IOException) for crfOid={} versionName={}: {}",
                    crf.getOid(), versionName, ioe.getMessage());
            return Result.failure(List.of("Could not read the spreadsheet: " + ioe.getMessage()));
        }

        // Parse-time validation errors collected by the parser. The
        // legacy code treats a non-empty errors list as "stop, don't
        // persist". We mirror that — return 400 with the messages.
        List<String> parseErrors = parsed.getErrors();
        if (parseErrors != null && !parseErrors.isEmpty()) {
            LOG.info("CRF parse rejected for crfOid={} versionName={} ({} errors)",
                    crf.getOid(), versionName, parseErrors.size());
            return Result.failure(new ArrayList<>(parseErrors));
        }

        // No errors → persist. The parser stages queries in NewCRFBean;
        // insertToDB() runs them in order under a JDBC transaction.
        try {
            parsed.insertToDB();
        } catch (Exception persistEx) {
            LOG.error("CRF parse insertToDB failed for crfOid={} versionName={}",
                    crf.getOid(), versionName, persistEx);
            return Result.failure(List.of(
                    "Persistence failed after parse: " + persistEx.getMessage()));
        }

        // The version row is now in the DB — look it up so we can hand
        // it back to the controller for the wire response.
        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        Integer newVersionId = versionDao.findCRFVersionId(crf.getId(),
                parsed.getVersionName() != null && !parsed.getVersionName().isBlank()
                        ? parsed.getVersionName() : versionName);
        if (newVersionId == null || newVersionId == 0) {
            // Insert succeeded but lookup failed — surfaces as a 500
            // because something's off in our assumption that
            // insertToDB writes a row with this (crfId, versionName).
            LOG.error("CRF parse persisted but version lookup failed for crfOid={} versionName={}",
                    crf.getOid(), versionName);
            return Result.failure(List.of(
                    "Parse persisted but the new version row could not be located. Check the audit log."));
        }
        CRFVersionBean persisted = versionDao.findByPK(newVersionId);

        // Bump the parent CRF's updated marker — mirrors legacy
        // CreateCRFVersionServlet:307-310.
        try {
            CRFDAO crfDao = new CRFDAO(dataSource);
            CRFBean refreshed = (CRFBean) crfDao.findByPK(crf.getId());
            refreshed.setUpdater(uploader);
            refreshed.setUpdatedDate(new java.util.Date());
            crfDao.update(refreshed);
        } catch (Exception bumpEx) {
            LOG.warn("Could not bump updated_date on parent CRF id={} (continuing): {}",
                    crf.getId(), bumpEx.getMessage());
        }

        return Result.success(persisted);
    }

    /**
     * Try the repeating parser first; fall back to classic if the
     * workbook lacks a {@code "groups"} sheet. Both parsers want a
     * fresh {@link FileInputStream}, so we open the file twice when
     * the fallback fires.
     */
    private NewCRFBean parse(Path storedFile,
                             CRFBean crf,
                             String versionName,
                             UserAccountBean uploader,
                             int studyId,
                             Locale locale,
                             ResourceBundle respage) throws IOException, CRFReadingException {
        try (FileInputStream firstStream = new FileInputStream(storedFile.toFile())) {
            SpreadSheetTableRepeating repeating = new SpreadSheetTableRepeating(
                    firstStream, uploader, versionName, locale, studyId);
            repeating.setMeasurementUnitDao(measurementUnitDao);

            if (repeating.isRepeating()) {
                repeating.setCrfId(crf.getId());
                NewCRFBean nib = repeating.toNewCRF(dataSource, respage);
                LOG.info("Parsed CRF via Repeating template: crfOid={} versionName={} items={}",
                        crf.getOid(), versionName, nib.getItems() == null ? 0 : nib.getItems().size());
                return nib;
            }
        }

        // No "groups" sheet → fall back to the classic parser.
        try (FileInputStream secondStream = new FileInputStream(storedFile.toFile())) {
            SpreadSheetTableClassic classic = new SpreadSheetTableClassic(
                    secondStream, uploader, versionName, locale, studyId);
            classic.setMeasurementUnitDao(measurementUnitDao);
            classic.setCrfId(crf.getId());
            NewCRFBean nib = classic.toNewCRF(dataSource, respage);
            LOG.info("Parsed CRF via Classic template: crfOid={} versionName={} items={}",
                    crf.getOid(), versionName, nib.getItems() == null ? 0 : nib.getItems().size());
            return nib;
        }
    }

    /**
     * Discriminated-union result type. {@link #success} carries the
     * persisted {@link CRFVersionBean}; {@link #errors} carries
     * parse-time validation messages a successful parse never has.
     */
    public static final class Result {
        private final CRFVersionBean success;
        private final List<String> errors;

        private Result(CRFVersionBean success, List<String> errors) {
            this.success = success;
            this.errors = errors;
        }

        static Result success(CRFVersionBean b) {
            return new Result(b, null);
        }

        static Result failure(List<String> errors) {
            return new Result(null, errors);
        }

        public boolean ok() { return success != null; }
        public CRFVersionBean version() { return success; }
        public List<String> errors() { return errors; }
    }
}
