/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.stream.Stream;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpServlet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.quartz.impl.StdScheduler;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ConfigurePasswordRequirementsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ConfigureServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.CreateCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.CreateCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.CreateJobExportServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.CreateJobImportServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.CreateUserAccountServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.DeleteCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.DeleteEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.EditStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.EditUserAccountServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.LegacyServletHarness;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RemoveCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RemoveCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RemoveStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RemoveSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RestoreCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RestoreCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RestoreStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RestoreSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.SetUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.UpdateCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.UpdateJobExportServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.UpdateJobImportServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.UpdateSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.CreateDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.CreateFiltersThreeServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.EditFilterServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ExportDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.RemoveDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.RemoveFilterServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.RestoreDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.ChangeStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.ContactServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.RequestAccountServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.RequestPasswordServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.RequestStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.ResetPasswordServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.UpdateProfileServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.AssignUserToStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ChangeDefinitionCRFOrdinalServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ChangeDefinitionOrdinalServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.CreateStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.CreateSubStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.CreateSubjectGroupClassServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.DefineStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.DeleteStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.LockCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.LockEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ReassignStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveSiteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveSubjectGroupClassServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreSiteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreSubjectGroupClassServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.SetStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.SignStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UnlockCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UnlockEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudyServletNew;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateSubStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateSubjectGroupClassServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewSectionDataEntryServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.AddNewSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.AdministrativeEditingServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CheckCRFLocked;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CreateDiscrepancyNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CreateNewStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CreateOneDiscrepancyNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.DoubleDataEntryServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.InitialDataEntryServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.MarkEventCRFCompleteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.RemoveRuleSetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.RestoreRuleSetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.RunRuleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.RunRuleSetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.TableOfContentsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.UpdateRuleSetRuleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.VerifyImportedCRFDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.VerifyImportedRuleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleAuditDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.ExecutionMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;

/**
 * The legacy servlets that change data on the request's parameters alone take
 * that request by POST only. A GET that would commit answers 405 with
 * {@code Allow: POST} before the servlet reads the session, the database, the
 * scheduler or the mail sender. The same request by POST still reaches the
 * servlet, and a GET that only renders (a confirmation page, a form, a list, a
 * dry run) is still served. What the POSTs write is covered by
 * {@code LegacyGetWritesPostOnlyDatabaseIT}; the five admin actions of the
 * first pass are in {@code LegacyAdminPostOnlyTest}.
 */
class LegacyGetWritesPostOnlyTest {

    private final UserAccountBean admin = LegacyServletHarness.sysAdmin(1, "root");

    private DataSource dataSource;
    private StdScheduler scheduler;
    private JavaMailSenderImpl mailSender;
    private SecurityManager securityManager;
    private RuleSetServiceInterface ruleSetService;
    private RuleSetRuleDao ruleSetRuleDao;
    private RuleSetRuleAuditDao ruleSetRuleAuditDao;
    private RuleSetDao ruleSetDao;
    private LegacyServletHarness harness;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        scheduler = mock(StdScheduler.class);
        mailSender = mock(JavaMailSenderImpl.class);
        securityManager = mock(SecurityManager.class);
        ruleSetService = mock(RuleSetServiceInterface.class);
        ruleSetRuleDao = mock(RuleSetRuleDao.class);
        ruleSetRuleAuditDao = mock(RuleSetRuleAuditDao.class);
        ruleSetDao = mock(RuleSetDao.class);
        harness = new LegacyServletHarness(dataSource)
                .bean("schedulerFactoryBean", scheduler)
                .bean("mailSender", mailSender)
                .bean("securityManager", securityManager)
                .bean("ruleSetService", ruleSetService)
                .bean("ruleSetRuleDao", ruleSetRuleDao)
                .bean("ruleSetRuleAuditDao", ruleSetRuleAuditDao)
                .bean("ruleSetDao", ruleSetDao);
    }

    /** A request with the given parameters, as name/value pairs. */
    private MockHttpServletRequest request(String method, String path, String... params) {
        MockHttpServletRequest req = harness.request(method, path, admin);
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        return req;
    }

    private MockHttpServletResponse run(Class<? extends HttpServlet> type, String method, String path, String... params)
            throws Exception {
        return harness.run(type.getDeclaredConstructor().newInstance(), request(method, path, params));
    }

    private static Arguments get(Class<? extends HttpServlet> type, String path, String... params) {
        return arguments(type, path, params);
    }

    /** Requests the servlets commit: each of them now takes a POST. */
    static Stream<Arguments> committingRequests() {
        return Stream.of(
                // Remove, restore, lock, delete and role actions: every request but
                // action=confirm (the confirmation page) commits.
                get(RemoveCRFServlet.class, "/RemoveCRF", "action", "submit", "id", "1"),
                get(RemoveCRFServlet.class, "/RemoveCRF", "id", "1"),
                get(RemoveCRFServlet.class, "/RemoveCRF", "action", "confirm ", "id", "1"),
                get(RestoreCRFServlet.class, "/RestoreCRF", "action", "submit", "id", "1"),
                get(RemoveCRFVersionServlet.class, "/RemoveCRFVersion", "action", "submit", "id", "1"),
                get(RestoreCRFVersionServlet.class, "/RestoreCRFVersion", "action", "submit", "id", "1"),
                get(DeleteCRFVersionServlet.class, "/DeleteCRFVersion", "action", "submit", "verId", "1"),
                get(DeleteEventCRFServlet.class, "/DeleteEventCRF", "action", "submit", "ecId", "1", "ssId", "1"),
                get(RemoveStudyServlet.class, "/RemoveStudy", "action", "submit", "id", "1"),
                get(RestoreStudyServlet.class, "/RestoreStudy", "action", "submit", "id", "1"),
                get(RemoveSubjectServlet.class, "/RemoveSubject", "action", "submit", "id", "1"),
                get(RestoreSubjectServlet.class, "/RestoreSubject", "action", "submit", "id", "1"),
                get(SetUserRoleServlet.class, "/SetUserRole", "userId", "2", "studyId", "1", "roleId", "4", "name", "physician"),
                get(RemoveEventCRFServlet.class, "/RemoveEventCRF", "action", "submit", "id", "1", "studySubId", "1"),
                get(RestoreEventCRFServlet.class, "/RestoreEventCRF", "action", "submit", "id", "1", "studySubId", "1"),
                get(RemoveEventDefinitionServlet.class, "/RemoveEventDefinition", "action", "submit", "id", "1"),
                get(RemoveEventDefinitionServlet.class, "/RemoveEventDefinition", "id", "1"),
                get(RestoreEventDefinitionServlet.class, "/RestoreEventDefinition", "action", "submit", "id", "1"),
                get(LockEventDefinitionServlet.class, "/LockEventDefinition", "action", "submit", "id", "1"),
                get(UnlockEventDefinitionServlet.class, "/UnlockEventDefinition", "action", "submit", "id", "1"),
                get(RemoveSiteServlet.class, "/RemoveSite", "action", "submit", "id", "2"),
                get(RestoreSiteServlet.class, "/RestoreSite", "action", "submit", "id", "2"),
                get(RemoveStudyEventServlet.class, "/RemoveStudyEvent", "action", "submit", "id", "1", "studySubId", "1"),
                get(RestoreStudyEventServlet.class, "/RestoreStudyEvent", "action", "submit", "id", "1", "studySubId", "1"),
                get(DeleteStudyEventServlet.class, "/DeleteStudyEvent", "action", "submit", "id", "1", "studySubId", "1"),
                get(RemoveStudySubjectServlet.class, "/RemoveStudySubject", "action", "submit", "id", "1", "subjectId", "1", "studyId", "1"),
                get(RestoreStudySubjectServlet.class, "/RestoreStudySubject", "action", "submit", "id", "1", "subjectId", "1", "studyId", "1"),
                get(RemoveStudyUserRoleServlet.class, "/RemoveStudyUserRole", "name", "physician", "studyId", "1"),
                get(RestoreStudyUserRoleServlet.class, "/RestoreStudyUserRole", "name", "physician", "studyId", "1"),
                get(SetStudyUserRoleServlet.class, "/SetStudyUserRole", "name", "physician", "studyId", "1", "roleId", "6"),
                get(RemoveSubjectGroupClassServlet.class, "/RemoveSubjectGroupClass", "action", "submit", "id", "1"),
                get(RestoreSubjectGroupClassServlet.class, "/RestoreSubjectGroupClass", "action", "submit", "id", "1"),
                get(RemoveRuleSetServlet.class, "/RemoveRuleSet", "ruleSetId", "1"),
                get(RemoveRuleSetServlet.class, "/RemoveRuleSet", "action", "CONFIRM", "ruleSetId", "1"),
                get(RestoreRuleSetServlet.class, "/RestoreRuleSet", "action", "submit", "ruleSetId", "1"),
                // Archiving a CRF version: the confirmation is the blank action, the commit action=confirm.
                get(LockCRFVersionServlet.class, "/LockCRFVersion", "action", "confirm", "id", "1"),
                get(UnlockCRFVersionServlet.class, "/UnlockCRFVersion", "action", "confirm", "id", "1"),
                // The links that committed: reordering, rule removal and restore, applying a rule run.
                get(ChangeDefinitionOrdinalServlet.class, "/ChangeDefinitionOrdinal", "current", "2", "previous", "1"),
                get(ChangeDefinitionCRFOrdinalServlet.class, "/ChangeDefinitionCRFOrdinal",
                        "current", "2", "previous", "1", "id", "1", "currentOrdinal", "2", "previousOrdinal", "1"),
                get(UpdateRuleSetRuleServlet.class, "/UpdateRuleSetRule", "action", "remove", "ruleSetRuleId", "1", "ruleSetId", "1"),
                get(UpdateRuleSetRuleServlet.class, "/UpdateRuleSetRule", "action", "restore", "ruleSetRuleId", "1", "ruleSetId", "1"),
                get(RunRuleSetServlet.class, "/RunRuleSet", "ruleSetId", "1", "dryRun", "no"),
                get(RunRuleServlet.class, "/RunRule", "crfId", "1", "action", "no"),
                get(RunRuleServlet.class, "/RunRule", "crfId", "1", "action", ""),
                // Exports and datasets.
                get(ExportDatasetServlet.class, "/ExportDataset", "action", "delete", "datasetId", "1", "adfId", "1"),
                get(ExportDatasetServlet.class, "/ExportDataset", "action", "odm", "datasetId", "1"),
                get(CreateDatasetServlet.class, "/CreateDataset", "action", "confirmall"),
                get(RemoveDatasetServlet.class, "/RemoveDataset", "action", "Remove this Dataset", "dsId", "1"),
                get(RestoreDatasetServlet.class, "/RestoreDataset", "action", "Restore this Dataset", "dsId", "1"),
                get(RemoveFilterServlet.class, "/RemoveFilter", "action", "Remove this Filter", "filterId", "1"),
                get(CreateFiltersThreeServlet.class, "/CreateFiltersThree", "action", "validate"),
                get(EditFilterServlet.class, "/EditFilter", "action", "validate", "filterId", "1"),
                // Notes, the read-only CRF's notes, data entry, profile, study switch, signing.
                get(CreateOneDiscrepancyNoteServlet.class, "/CreateOneDiscrepancyNote",
                        "parentId", "0", "name", "itemData", "id", "1", "field", "input1", "description0", "note", "typeId0", "3"),
                get(CreateDiscrepancyNoteServlet.class, "/CreateDiscrepancyNote",
                        "submitted", "1", "writeToDB", "1", "name", "subject", "id", "1", "description", "note", "typeId", "3"),
                get(ViewSectionDataEntryServlet.class, "/ViewSectionDataEntry", "action", "saveNotes", "ecId", "1"),
                get(InitialDataEntryServlet.class, "/InitialDataEntry", "submitted", "1", "eventCRFId", "1"),
                get(DoubleDataEntryServlet.class, "/DoubleDataEntry", "submitted", "1", "eventCRFId", "1"),
                get(AdministrativeEditingServlet.class, "/AdministrativeEditing", "submitted", "1", "eventCRFId", "1"),
                get(UpdateProfileServlet.class, "/UpdateProfile", "action", "submit"),
                get(UpdateProfileServlet.class, "/UpdateProfile", "action", "confirm", "firstName", "x"),
                get(ChangeStudyServlet.class, "/ChangeStudy", "action", "submit", "studyId", "1"),
                get(ChangeStudyServlet.class, "/ChangeStudy", "action", "confirm", "studyId", "1"),
                get(SignStudySubjectServlet.class, "/SignStudySubject", "action", "confirm", "id", "1", "j_user", "root", "j_pass", "x"),
                // Forms whose commit step was also accepted as a GET.
                get(ConfigureServlet.class, "/Configure", "submitted", "1", "lockswitch", "true", "lockcount", "3"),
                get(ConfigurePasswordRequirementsServlet.class, "/ConfigurePasswordRequirements", "submitted", "1"),
                get(CreateCRFServlet.class, "/CreateCRF", "action", "confirm", "name", "x"),
                get(UpdateCRFServlet.class, "/UpdateCRF", "action", "submit"),
                get(CreateCRFVersionServlet.class, "/CreateCRFVersion", "action", "confirmsql"),
                get(CreateJobExportServlet.class, "/CreateJobExport", "action", "confirmall"),
                get(CreateJobImportServlet.class, "/CreateJobImport", "action", "confirmall"),
                get(UpdateJobExportServlet.class, "/UpdateJobExport", "action", "confirmall", "tname", "nightly"),
                get(UpdateJobImportServlet.class, "/UpdateJobImport", "action", "confirmall", "tname", "nightly"),
                get(CreateUserAccountServlet.class, "/CreateUserAccount", "submitted", "1", "userName", "x"),
                get(EditUserAccountServlet.class, "/EditUserAccount", "submitted", "1", "userId", "2"),
                get(EditStudyUserRoleServlet.class, "/EditStudyUserRole", "submitted", "1", "studyId", "1", "userName", "physician"),
                get(UpdateSubjectServlet.class, "/UpdateSubject", "action", "submit", "id", "1"),
                get(UpdateSubjectServlet.class, "/UpdateSubject", "id", "1"),
                get(ContactServlet.class, "/Contact", "action", "submit", "name", "x", "email", "x@example.invalid",
                        "subject", "x", "message", "x"),
                get(RequestAccountServlet.class, "/RequestAccount", "action", "submit"),
                get(RequestStudyServlet.class, "/RequestStudy", "action", "submit"),
                get(RequestPasswordServlet.class, "/RequestPassword", "action", "confirm", "name", "root", "email", "x@example.invalid"),
                get(ResetPasswordServlet.class, "/ResetPassword", "mustChangePwd", "no", "oldPasswd", "x"),
                get(AssignUserToStudyServlet.class, "/AssignUserToStudy", "action", "submit"),
                get(CreateStudyServlet.class, "/CreateStudy", "action", "submit"),
                get(CreateStudyServlet.class, "/CreateStudy", "action", "next", "pageNum", "1"),
                get(CreateSubStudyServlet.class, "/CreateSubStudy", "action", "submit"),
                get(CreateSubjectGroupClassServlet.class, "/CreateSubjectGroupClass", "action", "submit"),
                get(DefineStudyEventServlet.class, "/DefineStudyEvent", "actionName", "submit"),
                get(ReassignStudySubjectServlet.class, "/ReassignStudySubject", "action", "submit", "id", "1", "studyId", "1"),
                get(UpdateEventDefinitionServlet.class, "/UpdateEventDefinition", "action", "submit"),
                get(UpdateStudyServlet.class, "/UpdateStudy", "action", "submit"),
                get(UpdateStudyServletNew.class, "/UpdateStudyNew", "action", "submit", "id", "1"),
                get(UpdateStudySubjectServlet.class, "/UpdateStudySubject", "action", "submit", "id", "1"),
                get(UpdateSubStudyServlet.class, "/UpdateSubStudy", "action", "confirm"),
                get(UpdateSubjectGroupClassServlet.class, "/UpdateSubjectGroupClass", "submitted", "1", "action", "submit", "id", "1"),
                get(UpdateStudyEventServlet.class, "/UpdateStudyEvent", "action", "submit", "event_id", "1", "ss_id", "1"),
                get(UpdateStudyEventServlet.class, "/UpdateStudyEvent", "action", "confirm", "event_id", "1", "ss_id", "1"),
                get(AddNewSubjectServlet.class, "/AddNewSubject", "submitted", "1", "label", "x"),
                get(CreateNewStudyEventServlet.class, "/CreateNewStudyEvent", "submitted", "1"),
                get(MarkEventCRFCompleteServlet.class, "/MarkEventCRFComplete", "submitted", "1", "ecId", "1"),
                get(TableOfContentsServlet.class, "/TableOfContents", "action", "ide_s", "ecId", "1"),
                get(TableOfContentsServlet.class, "/TableOfContents", "action", "ide_c", "ecId", "1", "submitted", "1"),
                get(VerifyImportedCRFDataServlet.class, "/VerifyImportedCRFData", "action", "save"),
                get(VerifyImportedRuleServlet.class, "/VerifyImportedRule", "action", "save"),
                get(CheckCRFLocked.class, "/CheckCRFLocked", "userId", "1", "exitTo", "ListStudySubjects"));
    }

    @ParameterizedTest
    @MethodSource("committingRequests")
    void aCommittingGetIsRefusedBeforeTheServletRuns(Class<? extends HttpServlet> type, String path, String[] params)
            throws Exception {
        MockHttpServletResponse resp = run(type, "GET", path, params);

        verifyNoInteractions(dataSource, scheduler, mailSender, securityManager, ruleSetService, ruleSetRuleDao,
                ruleSetRuleAuditDao, ruleSetDao);
        assertNull(resp.getForwardedUrl(), "no page was rendered");
        assertEquals(405, resp.getStatus());
        assertEquals("POST", resp.getHeader("Allow"));
    }

    @ParameterizedTest
    @MethodSource("committingRequests")
    void theSameRequestByPostReachesTheServlet(Class<? extends HttpServlet> type, String path, String[] params)
            throws Exception {
        MockHttpServletResponse resp = run(type, "POST", path, params);

        assertNotEquals(405, resp.getStatus(), "the guard applies to GET only");
    }

    /** Requests that only render a page: they keep answering GET. */
    static Stream<Arguments> renderingGets() {
        return Stream.of(
                get(RemoveCRFServlet.class, "/RemoveCRF", "action", "confirm", "id", "1"),
                get(RemoveStudySubjectServlet.class, "/RemoveStudySubject", "action", "confirm", "id", "1"),
                get(RemoveEventDefinitionServlet.class, "/RemoveEventDefinition", "action", "CONFIRM", "id", "1"),
                get(RemoveRuleSetServlet.class, "/RemoveRuleSet", "action", "confirm", "ruleSetId", "1"),
                get(SetUserRoleServlet.class, "/SetUserRole", "action", "confirm", "userId", "2"),
                get(SetUserRoleServlet.class, "/SetUserRole", "changeRoles", "true", "userId", "2"),
                get(LockCRFVersionServlet.class, "/LockCRFVersion", "id", "1"),
                get(UnlockCRFVersionServlet.class, "/UnlockCRFVersion", "id", "1"),
                get(RunRuleSetServlet.class, "/RunRuleSet", "ruleSetId", "1"),
                get(RunRuleServlet.class, "/RunRule", "crfId", "1", "action", "dryRun"),
                get(ExportDatasetServlet.class, "/ExportDataset", "datasetId", "1"),
                get(CreateDatasetServlet.class, "/CreateDataset"),
                get(RemoveDatasetServlet.class, "/RemoveDataset", "dsId", "1"),
                get(EditFilterServlet.class, "/EditFilter", "filterId", "1"),
                get(CreateDiscrepancyNoteServlet.class, "/CreateDiscrepancyNote", "name", "subject", "id", "1"),
                get(ViewSectionDataEntryServlet.class, "/ViewSectionDataEntry", "ecId", "1", "tabId", "1"),
                get(InitialDataEntryServlet.class, "/InitialDataEntry", "eventCRFId", "1"),
                get(UpdateProfileServlet.class, "/UpdateProfile"),
                get(ChangeStudyServlet.class, "/ChangeStudy"),
                get(SignStudySubjectServlet.class, "/SignStudySubject", "id", "1"),
                get(ConfigureServlet.class, "/Configure"),
                get(CreateCRFVersionServlet.class, "/CreateCRFVersion", "action", "delete"),
                get(CreateUserAccountServlet.class, "/CreateUserAccount", "submitted", "1", "changeRoles", "true"),
                get(UpdateJobExportServlet.class, "/UpdateJobExport", "tname", "nightly"),
                get(UpdateSubjectServlet.class, "/UpdateSubject", "action", "show", "id", "1"),
                get(ContactServlet.class, "/Contact"),
                get(AssignUserToStudyServlet.class, "/AssignUserToStudy", "action", "submit", "next_list_page", "true"),
                get(ReassignStudySubjectServlet.class, "/ReassignStudySubject", "action", "confirm", "id", "1"),
                get(UpdateStudyServletNew.class, "/UpdateStudyNew", "id", "1"),
                get(UpdateStudySubjectServlet.class, "/UpdateStudySubject", "action", "show", "id", "1"),
                get(UpdateSubjectGroupClassServlet.class, "/UpdateSubjectGroupClass", "id", "1"),
                get(UpdateStudyEventServlet.class, "/UpdateStudyEvent", "event_id", "1", "ss_id", "1"),
                get(AddNewSubjectServlet.class, "/AddNewSubject", "instr", "1"),
                get(TableOfContentsServlet.class, "/TableOfContents", "action", "ide_c", "ecId", "1"),
                get(CheckCRFLocked.class, "/CheckCRFLocked", "ecId", "1"));
    }

    @ParameterizedTest
    @MethodSource("renderingGets")
    void aGetThatOnlyRendersIsServed(Class<? extends HttpServlet> type, String path, String[] params) throws Exception {
        MockHttpServletResponse resp = run(type, "GET", path, params);

        assertNotEquals(405, resp.getStatus());
    }

    // ---- the actions whose callers were links now post; the POST still acts ----

    @Test
    void aPostRemovesARule() throws Exception {
        // The rule set rule and rule set are the session study's (study 1).
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet owner = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(owner);
        when(owner.next()).thenReturn(true);
        when(owner.getInt(1)).thenReturn(LegacyServletHarness.STUDY_ID);
        RuleSetRuleBean rule = new RuleSetRuleBean();
        rule.setStatus(Status.AVAILABLE);
        when(ruleSetRuleDao.findById(7)).thenReturn(rule);
        when(ruleSetRuleDao.saveOrUpdate(rule)).thenReturn(rule);

        run(UpdateRuleSetRuleServlet.class, "POST", "/UpdateRuleSetRule", "action", "remove", "ruleSetRuleId", "7", "ruleSetId", "3");

        verify(ruleSetRuleDao).saveOrUpdate(rule);
        assertEquals(Status.DELETED, rule.getStatus());
    }

    @Test
    void aPostAppliesTheRuleSetAndAGetOnlyDryRunsIt() throws Exception {
        RuleSetBean ruleSet = new RuleSetBean();
        when(ruleSetService.getRuleSetById(any(), eq("3"))).thenReturn(ruleSet);

        run(RunRuleSetServlet.class, "GET", "/RunRuleSet", "ruleSetId", "3");
        verify(ruleSetService).runRulesInBulk(anyList(), eq(true), any(), any(), anyBoolean());
        verify(ruleSetService, never()).runRulesInBulk(anyList(), eq(false), any(), any(), anyBoolean());

        run(RunRuleSetServlet.class, "POST", "/RunRuleSet", "ruleSetId", "3", "dryRun", "no");
        verify(ruleSetService).runRulesInBulk(anyList(), eq(false), any(), any(), anyBoolean());
    }

    @Test
    void aPostAppliesTheCrfRulesAndAGetOnlyDryRunsThem() throws Exception {
        run(RunRuleServlet.class, "GET", "/RunRule", "crfId", "5", "action", "dryRun");
        verify(ruleSetService).runRulesInBulk(eq("5"), eq(ExecutionMode.DRY_RUN), any(), any());
        verify(ruleSetService, never()).runRulesInBulk(eq("5"), eq(ExecutionMode.SAVE), any(), any());

        run(RunRuleServlet.class, "POST", "/RunRule", "crfId", "5", "action", "no");
        verify(ruleSetService).runRulesInBulk(eq("5"), eq(ExecutionMode.SAVE), any(), any());
    }
}
