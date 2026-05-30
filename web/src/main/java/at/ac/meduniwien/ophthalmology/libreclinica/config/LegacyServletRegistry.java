package at.ac.meduniwien.ophthalmology.libreclinica.config;

import jakarta.servlet.ServletRegistration;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.ac.meduniwien.ophthalmology.libreclinica.control.MainMenuServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.AdminSystemServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.AuditDatabaseServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.AuditLogStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.AuditLogUserServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.AuditUserActivityDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.AuditUserActivityServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.BatchCRFMigrationServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ConfigurePasswordRequirementsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ConfigureServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.CreateCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.CreateCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.CreateJobExportServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.CreateJobImportServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.CreateUserAccountServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.CreateXformCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.DeleteCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.DeleteEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.DeleteStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.DeleteUserServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.DownloadStudyMetadataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.DownloadVersionSpreadSheetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.EditStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.EditUserAccountServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.InitCreateCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.InitUpdateCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ListCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ListSubjectDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ListSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ListUserAccountsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.PauseJobServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.PrintoutCertificateServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RemoveCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RemoveCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RemoveStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RemoveSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RestoreCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RestoreCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RestoreStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.RestoreSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.SendTestEmailServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.SetUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.SystemStatusServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.UnLockUserServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.UpdateCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.UpdateJobExportServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.UpdateJobImportServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.UpdateSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ViewAllJobsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ViewCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ViewImportJobServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ViewJobServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ViewLogMessageServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ViewSingleJobServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ViewStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ViewSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.ViewUserAccountServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.AccessFileServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ApplyFilterServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ChooseDownloadFormat;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.CreateDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.CreateFiltersOneServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.CreateFiltersThreeServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.CreateFiltersTwoServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.DiscrepancyNoteOutputServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.EditDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.EditFilterServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.EditSelectedServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ExportDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ExtractDatasetsMainServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.RemoveDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.RemoveFilterServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.RestoreDatasetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.SelectItemsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ShowFileServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ViewDatasetsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ViewItemDetailServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.extract.ViewSelectedServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.ChangeStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.ContactServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.EnterpriseServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.LogoutServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.RequestAccountServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.RequestPasswordServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.RequestStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.ResetPasswordServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.UpdateProfileServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.AddCRFToDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.AssignUserToStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ChangeDefinitionCRFOrdinalServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ChangeDefinitionOrdinalServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.CreateStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.CreateSubStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.CreateSubjectGroupClassServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.DefineStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.DeleteStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ExportExcelStudySubjectAuditLogServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.InitUpdateEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.InitUpdateStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.InitUpdateSubStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListDiscNotesForCRFDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListDiscNotesForCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListEventsForSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListEventsForSubjectsDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListEventsForSubjectsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListSiteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListStudySubjectsManageServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListStudyUserServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListSubjectGroupClassServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.LockCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.LockEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ManageStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.PrintAllEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.PrintAllSiteEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.PrintCRFByIdServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.PrintCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.PrintDataEntryServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.PrintEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ReassignStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveCRFFromDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveSiteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveSubjectGroupClassServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ResolveDiscrepancyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreCRFFromDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreSiteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RestoreSubjectGroupClassServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.SetStudyUserRoleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.SignStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.StudyAuditLogDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.StudyAuditLogServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UnlockCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UnlockEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudyServletNew;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateSubStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateSubjectGroupClassServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewCRFVersionPreview;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewCRFVersionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewEventCRFContentServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewEventCRFServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewEventDefinitionReadOnlyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewEventDefinitionServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewItemAuditLogServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewNotesDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewNotesServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewSectionDataEntryByIdServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewSectionDataEntryPreview;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewSectionDataEntryRESTUrlServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewSectionDataEntryServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewSiteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewStudyEventsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewStudySubjectAuditLogServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewStudyUserServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewSubjectGroupClassServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewTableOfContentServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.rule.ExecuteCrossEditCheckServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.AddNewSubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.AdministrativeEditingServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CheckCRFLocked;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CreateDiscrepancyNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CreateNewStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CreateOneDiscrepancyNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.DoubleDataEntryServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.DownloadAttachedFileServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.DownloadRuleSetXmlServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.EnterDataForStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.FindStudyEventServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.FindSubjectsDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ImportCRFDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ImportRuleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.InitialDataEntryServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ListStudySubjectsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ListStudySubjectsSubmitServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.MarkEventCRFCompleteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.MatchPasswordServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ParticipantFormServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.RemoveRuleSetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.RestoreRuleSetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.RunRuleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.RunRuleSetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.SubmitDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.TableOfContentsServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.TestRuleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.UpdateRuleSetRuleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.UploadFileServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.VerifyImportedCRFDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.VerifyImportedRuleServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ViewDiscrepancyNoteServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ViewRuleAssignmentDataServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ViewRuleAssignmentNewServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ViewRuleAssignmentServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ViewRuleSetAuditServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ViewRuleSetServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.techadmin.TechAdminServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.techadmin.ViewSchedulerServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.view.form.FormServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.web.SQLInitServlet;

/**
 * Phase C.16 (2026-05-30): Java replacement for the 215 legacy LibreClinica
 * servlet declarations in {@code web.xml}. Single
 * {@link ServletContextInitializer} @Bean registers each legacy servlet via
 * {@code ServletContext.addServlet(name, Class)} — Tomcat then
 * <strong>lazily</strong> instantiates the servlet on its first request,
 * matching the original web.xml lifecycle. Pre-instantiating via
 * {@code new ServletRegistrationBean(new MyServlet())} at @Bean creation
 * time fires servlet static initializers too early (some reference
 * ResourceBundles loaded later by the application context — e.g.
 * {@code CreateDiscrepancyNoteServlet.resexception}).
 * <p>
 * Skipped:
 * <ul>
 *   <li>{@code pages} ({@code DispatcherServlet}) — stays as a {@code <servlet>}
 *       entry in {@code web.xml}; loads pages-servlet.xml → WebMvcConfig.</li>
 *   <li>{@code ws} ({@code MessageDispatcherServlet}) + {@code OpenClinicaJersey} /
 *       {@code OpenClinicaJersey2} ({@code SpringServlet}) — zombies; underlying
 *       frameworks reference javax.servlet.Filter and fail to link against
 *       jakarta.servlet 6. Tomcat marked them unavailable at deploy time.</li>
 *   <li>{@code DataEntryServlet} — abstract; concrete subclasses
 *       (InitialDataEntryServlet, etc.) handle the actual routes.</li>
 *   <li>{@code urlRewriterServlet} ({@code UrlRewriteServlet}) +
 *       {@code OpenRosaFormDownloadServlet} — class no longer exists in
 *       the source tree (removed in earlier phases without web.xml cleanup).</li>
 * </ul>
 */
@Configuration
public class LegacyServletRegistry {

    @Bean
    public ServletContextInitializer legacyServletsInitializer() {
        return ctx -> {
            ServletRegistration.Dynamic reg0 = ctx.addServlet("AccessFileServlet", AccessFileServlet.class);
            reg0.addMapping("/AccessFile");

            ServletRegistration.Dynamic reg1 = ctx.addServlet("AddCRFToDefinitionServlet", AddCRFToDefinitionServlet.class);
            reg1.addMapping("/AddCRFToDefinition");

            ServletRegistration.Dynamic reg2 = ctx.addServlet("AddNewSubjectServlet", AddNewSubjectServlet.class);
            reg2.addMapping("/AddNewSubject");

            ServletRegistration.Dynamic reg3 = ctx.addServlet("AdminSystemServlet", AdminSystemServlet.class);
            reg3.addMapping("/AdminSystem");

            ServletRegistration.Dynamic reg4 = ctx.addServlet("AdministrativeEditingServlet", AdministrativeEditingServlet.class);
            reg4.addMapping("/AdministrativeEditing");

            ServletRegistration.Dynamic reg5 = ctx.addServlet("ApplyFilterServlet", ApplyFilterServlet.class);
            reg5.addMapping("/ApplyFilter");

            ServletRegistration.Dynamic reg6 = ctx.addServlet("AssignUserToStudyServlet", AssignUserToStudyServlet.class);
            reg6.addMapping("/AssignUserToStudy");

            ServletRegistration.Dynamic reg7 = ctx.addServlet("AuditDatabaseServlet", AuditDatabaseServlet.class);
            reg7.addMapping("/AuditDatabase");

            ServletRegistration.Dynamic reg8 = ctx.addServlet("AuditLogStudyServlet", AuditLogStudyServlet.class);
            reg8.addMapping("/AuditLogStudy");

            ServletRegistration.Dynamic reg9 = ctx.addServlet("AuditLogUserServlet", AuditLogUserServlet.class);
            reg9.addMapping("/AuditLogUser");

            ServletRegistration.Dynamic reg10 = ctx.addServlet("AuditUserActivityDataServlet", AuditUserActivityDataServlet.class);
            reg10.addMapping("/AuditUserActivityData");

            ServletRegistration.Dynamic reg11 = ctx.addServlet("AuditUserActivityServlet", AuditUserActivityServlet.class);
            reg11.addMapping("/AuditUserActivity");

            ServletRegistration.Dynamic reg12 = ctx.addServlet("BatchCRFMigrationServlet", BatchCRFMigrationServlet.class);
            reg12.addMapping("/BatchCRFMigration");

            ServletRegistration.Dynamic reg13 = ctx.addServlet("ChangeDefinitionCRFOrdinalServlet", ChangeDefinitionCRFOrdinalServlet.class);
            reg13.addMapping("/ChangeDefinitionCRFOrdinal");

            ServletRegistration.Dynamic reg14 = ctx.addServlet("ChangeDefinitionOrdinalServlet", ChangeDefinitionOrdinalServlet.class);
            reg14.addMapping("/ChangeDefinitionOrdinal");

            ServletRegistration.Dynamic reg15 = ctx.addServlet("ChangeStudyServlet", ChangeStudyServlet.class);
            reg15.addMapping("/ChangeStudy");

            ServletRegistration.Dynamic reg16 = ctx.addServlet("CheckCRFLocked", CheckCRFLocked.class);
            reg16.addMapping("/CheckCRFLocked");

            ServletRegistration.Dynamic reg17 = ctx.addServlet("ChooseDownloadFormat", ChooseDownloadFormat.class);
            reg17.addMapping("/ChooseDownloadFormat");

            ServletRegistration.Dynamic reg18 = ctx.addServlet("ConfigurePasswordRequirementsServlet", ConfigurePasswordRequirementsServlet.class);
            reg18.addMapping("/ConfigurePasswordRequirements");

            ServletRegistration.Dynamic reg19 = ctx.addServlet("ConfigureServlet", ConfigureServlet.class);
            reg19.addMapping("/Configure");

            ServletRegistration.Dynamic reg20 = ctx.addServlet("ContactServlet", ContactServlet.class);
            reg20.addMapping("/Contact");

            ServletRegistration.Dynamic reg21 = ctx.addServlet("CreateCRFServlet", CreateCRFServlet.class);
            reg21.addMapping("/CreateCRF");

            ServletRegistration.Dynamic reg22 = ctx.addServlet("CreateCRFVersionServlet", CreateCRFVersionServlet.class);
            reg22.addMapping("/CreateCRFVersion");

            ServletRegistration.Dynamic reg23 = ctx.addServlet("CreateDatasetServlet", CreateDatasetServlet.class);
            reg23.addMapping("/CreateDataset");

            ServletRegistration.Dynamic reg24 = ctx.addServlet("CreateDiscrepancyNoteServlet", CreateDiscrepancyNoteServlet.class);
            reg24.addMapping("/CreateDiscrepancyNote");

            ServletRegistration.Dynamic reg25 = ctx.addServlet("CreateFiltersOneServlet", CreateFiltersOneServlet.class);
            reg25.addMapping("/CreateFiltersOne");

            ServletRegistration.Dynamic reg26 = ctx.addServlet("CreateFiltersThreeServlet", CreateFiltersThreeServlet.class);
            reg26.addMapping("/CreateFiltersThree");

            ServletRegistration.Dynamic reg27 = ctx.addServlet("CreateFiltersTwoServlet", CreateFiltersTwoServlet.class);
            reg27.addMapping("/CreateFiltersTwo");

            ServletRegistration.Dynamic reg28 = ctx.addServlet("CreateJobExportServlet", CreateJobExportServlet.class);
            reg28.addMapping("/CreateJobExport");

            ServletRegistration.Dynamic reg29 = ctx.addServlet("CreateJobImportServlet", CreateJobImportServlet.class);
            reg29.addMapping("/CreateJobImport");

            ServletRegistration.Dynamic reg30 = ctx.addServlet("CreateNewStudyEventServlet", CreateNewStudyEventServlet.class);
            reg30.addMapping("/CreateNewStudyEvent");

            ServletRegistration.Dynamic reg31 = ctx.addServlet("CreateOneDiscrepancyNoteServlet", CreateOneDiscrepancyNoteServlet.class);
            reg31.addMapping("/CreateOneDiscrepancyNote");

            ServletRegistration.Dynamic reg32 = ctx.addServlet("CreateStudyServlet", CreateStudyServlet.class);
            reg32.addMapping("/CreateStudy");

            ServletRegistration.Dynamic reg33 = ctx.addServlet("CreateSubStudyServlet", CreateSubStudyServlet.class);
            reg33.addMapping("/CreateSubStudy");

            ServletRegistration.Dynamic reg34 = ctx.addServlet("CreateSubjectGroupClassServlet", CreateSubjectGroupClassServlet.class);
            reg34.addMapping("/CreateSubjectGroupClass");

            ServletRegistration.Dynamic reg35 = ctx.addServlet("CreateUserAccountServlet", CreateUserAccountServlet.class);
            reg35.addMapping("/CreateUserAccount");

            ServletRegistration.Dynamic reg36 = ctx.addServlet("CreateXformCRFVersionServlet", CreateXformCRFVersionServlet.class);
            reg36.addMapping("/CreateXformCRFVersion");

            ServletRegistration.Dynamic reg37 = ctx.addServlet("DefineStudyEventServlet", DefineStudyEventServlet.class);
            reg37.addMapping("/DefineStudyEvent");

            ServletRegistration.Dynamic reg38 = ctx.addServlet("DeleteCRFVersionServlet", DeleteCRFVersionServlet.class);
            reg38.addMapping("/DeleteCRFVersion");

            ServletRegistration.Dynamic reg39 = ctx.addServlet("DeleteEventCRFServlet", DeleteEventCRFServlet.class);
            reg39.addMapping("/DeleteEventCRF");

            ServletRegistration.Dynamic reg40 = ctx.addServlet("DeleteStudyEventServlet", DeleteStudyEventServlet.class);
            reg40.addMapping("/DeleteStudyEvent");

            ServletRegistration.Dynamic reg41 = ctx.addServlet("DeleteStudyUserRoleServlet", DeleteStudyUserRoleServlet.class);
            reg41.addMapping("/DeleteStudyUserRole");

            ServletRegistration.Dynamic reg42 = ctx.addServlet("DeleteUserServlet", DeleteUserServlet.class);
            reg42.addMapping("/DeleteUser");

            ServletRegistration.Dynamic reg43 = ctx.addServlet("DiscrepancyNoteOutputServlet", DiscrepancyNoteOutputServlet.class);
            reg43.addMapping("/DiscrepancyNoteOutputServlet");

            ServletRegistration.Dynamic reg44 = ctx.addServlet("DoubleDataEntryServlet", DoubleDataEntryServlet.class);
            reg44.addMapping("/DoubleDataEntry");

            ServletRegistration.Dynamic reg45 = ctx.addServlet("DownloadAttachedFileServlet", DownloadAttachedFileServlet.class);
            reg45.addMapping("/DownloadAttachedFile");

            ServletRegistration.Dynamic reg46 = ctx.addServlet("DownloadRuleSetXmlServlet", DownloadRuleSetXmlServlet.class);
            reg46.addMapping("/DownloadRuleSetXml");

            ServletRegistration.Dynamic reg47 = ctx.addServlet("DownloadStudyMetadataServlet", DownloadStudyMetadataServlet.class);
            reg47.addMapping("/DownloadStudyMetadata");

            ServletRegistration.Dynamic reg48 = ctx.addServlet("DownloadVersionSpreadSheetServlet", DownloadVersionSpreadSheetServlet.class);
            reg48.addMapping("/DownloadVersionSpreadSheet");

            ServletRegistration.Dynamic reg49 = ctx.addServlet("EditDatasetServlet", EditDatasetServlet.class);
            reg49.addMapping("/EditDataset");

            ServletRegistration.Dynamic reg50 = ctx.addServlet("EditFilterServlet", EditFilterServlet.class);
            reg50.addMapping("/EditFilter");

            ServletRegistration.Dynamic reg51 = ctx.addServlet("EditSelectedServlet", EditSelectedServlet.class);
            reg51.addMapping("/EditSelected");

            ServletRegistration.Dynamic reg52 = ctx.addServlet("EditStudyUserRoleServlet", EditStudyUserRoleServlet.class);
            reg52.addMapping("/EditStudyUserRole");

            ServletRegistration.Dynamic reg53 = ctx.addServlet("EditUserAccountServlet", EditUserAccountServlet.class);
            reg53.addMapping("/EditUserAccount");

            ServletRegistration.Dynamic reg54 = ctx.addServlet("EnterDataForStudyEventServlet", EnterDataForStudyEventServlet.class);
            reg54.addMapping("/EnterDataForStudyEvent");

            ServletRegistration.Dynamic reg55 = ctx.addServlet("EnterpriseServlet", EnterpriseServlet.class);
            reg55.addMapping("/Enterprise");

            ServletRegistration.Dynamic reg56 = ctx.addServlet("ExecuteCrossEditCheckServlet", ExecuteCrossEditCheckServlet.class);
            reg56.addMapping("/ExecuteCrossEditCheck");

            ServletRegistration.Dynamic reg57 = ctx.addServlet("ExportDatasetServlet", ExportDatasetServlet.class);
            reg57.addMapping("/ExportDataset");

            ServletRegistration.Dynamic reg58 = ctx.addServlet("ExportExcelStudySubjectAuditLogServlet", ExportExcelStudySubjectAuditLogServlet.class);
            reg58.addMapping("/ExportExcelStudySubjectAuditLog");

            ServletRegistration.Dynamic reg59 = ctx.addServlet("ExtractDatasetsMainServlet", ExtractDatasetsMainServlet.class);
            reg59.addMapping("/ExtractDatasetsMain");

            ServletRegistration.Dynamic reg60 = ctx.addServlet("FindStudyEventServlet", FindStudyEventServlet.class);
            reg60.addMapping("/FindStudyEvent");

            ServletRegistration.Dynamic reg61 = ctx.addServlet("FindSubjectsDataServlet", FindSubjectsDataServlet.class);
            reg61.addMapping("/FindSubjectsData");

            ServletRegistration.Dynamic reg62 = ctx.addServlet("FormServlet", FormServlet.class);
            reg62.addMapping("/form");

            ServletRegistration.Dynamic reg63 = ctx.addServlet("ImportCRFDataServlet", ImportCRFDataServlet.class);
            reg63.addMapping("/ImportCRFData");

            ServletRegistration.Dynamic reg64 = ctx.addServlet("ImportRuleServlet", ImportRuleServlet.class);
            reg64.addMapping("/ImportRule");

            ServletRegistration.Dynamic reg65 = ctx.addServlet("InitCreateCRFVersionServlet", InitCreateCRFVersionServlet.class);
            reg65.addMapping("/InitCreateCRFVersion");

            ServletRegistration.Dynamic reg66 = ctx.addServlet("InitUpdateCRFServlet", InitUpdateCRFServlet.class);
            reg66.addMapping("/InitUpdateCRF");

            ServletRegistration.Dynamic reg67 = ctx.addServlet("InitUpdateEventDefinitionServlet", InitUpdateEventDefinitionServlet.class);
            reg67.addMapping("/InitUpdateEventDefinition");

            ServletRegistration.Dynamic reg68 = ctx.addServlet("InitUpdateStudyServlet", InitUpdateStudyServlet.class);
            reg68.addMapping("/InitUpdateStudy");

            ServletRegistration.Dynamic reg69 = ctx.addServlet("InitUpdateSubStudyServlet", InitUpdateSubStudyServlet.class);
            reg69.addMapping("/InitUpdateSubStudy");

            ServletRegistration.Dynamic reg70 = ctx.addServlet("InitialDataEntryServlet", InitialDataEntryServlet.class);
            reg70.addMapping("/InitialDataEntry");

            ServletRegistration.Dynamic reg71 = ctx.addServlet("ListCRFServlet", ListCRFServlet.class);
            reg71.addMapping("/ListCRF");

            ServletRegistration.Dynamic reg72 = ctx.addServlet("ListDiscNotesForCRFDataServlet", ListDiscNotesForCRFDataServlet.class);
            reg72.addMapping("/ListDiscNotesForCRFData");

            ServletRegistration.Dynamic reg73 = ctx.addServlet("ListDiscNotesForCRFServlet", ListDiscNotesForCRFServlet.class);
            reg73.addMapping("/ListDiscNotesForCRFServlet");

            ServletRegistration.Dynamic reg74 = ctx.addServlet("ListEventDefinitionServlet", ListEventDefinitionServlet.class);
            reg74.addMapping("/ListEventDefinition");

            ServletRegistration.Dynamic reg75 = ctx.addServlet("ListEventsForSubjectServlet", ListEventsForSubjectServlet.class);
            reg75.addMapping("/ListEventsForSubject");

            ServletRegistration.Dynamic reg76 = ctx.addServlet("ListEventsForSubjectsDataServlet", ListEventsForSubjectsDataServlet.class);
            reg76.addMapping("/ListEventsForSubjectsData");

            ServletRegistration.Dynamic reg77 = ctx.addServlet("ListEventsForSubjectsServlet", ListEventsForSubjectsServlet.class);
            reg77.addMapping("/ListEventsForSubjects");

            ServletRegistration.Dynamic reg78 = ctx.addServlet("ListSiteServlet", ListSiteServlet.class);
            reg78.addMapping("/ListSite");

            ServletRegistration.Dynamic reg79 = ctx.addServlet("ListStudyServlet", ListStudyServlet.class);
            reg79.addMapping("/ListStudy");

            ServletRegistration.Dynamic reg80 = ctx.addServlet("ListStudySubjectsManageServlet", ListStudySubjectsManageServlet.class);
            reg80.addMapping("/ListStudySubject");

            ServletRegistration.Dynamic reg81 = ctx.addServlet("ListStudySubjectsServlet", ListStudySubjectsServlet.class);
            reg81.addMapping("/ListStudySubjects");

            ServletRegistration.Dynamic reg82 = ctx.addServlet("ListStudySubjectsSubmitServlet", ListStudySubjectsSubmitServlet.class);
            reg82.addMapping("/ListStudySubjectsSubmit");

            ServletRegistration.Dynamic reg83 = ctx.addServlet("ListStudyUserServlet", ListStudyUserServlet.class);
            reg83.addMapping("/ListStudyUser");

            ServletRegistration.Dynamic reg84 = ctx.addServlet("ListSubjectDataServlet", ListSubjectDataServlet.class);
            reg84.addMapping("/ListSubjectData");

            ServletRegistration.Dynamic reg85 = ctx.addServlet("ListSubjectGroupClassServlet", ListSubjectGroupClassServlet.class);
            reg85.addMapping("/ListSubjectGroupClass");

            ServletRegistration.Dynamic reg86 = ctx.addServlet("ListSubjectServlet", ListSubjectServlet.class);
            reg86.addMapping("/ListSubject");

            ServletRegistration.Dynamic reg87 = ctx.addServlet("ListUserAccountsServlet", ListUserAccountsServlet.class);
            reg87.addMapping("/ListUserAccounts");

            ServletRegistration.Dynamic reg88 = ctx.addServlet("LockCRFVersionServlet", LockCRFVersionServlet.class);
            reg88.addMapping("/LockCRFVersion");

            ServletRegistration.Dynamic reg89 = ctx.addServlet("LockEventDefinitionServlet", LockEventDefinitionServlet.class);
            reg89.addMapping("/LockEventDefinition");

            ServletRegistration.Dynamic reg90 = ctx.addServlet("LogoutServlet", LogoutServlet.class);
            reg90.addMapping("/Logout");

            ServletRegistration.Dynamic reg91 = ctx.addServlet("MainMenuServlet", MainMenuServlet.class);
            reg91.addMapping("/MainMenu");

            ServletRegistration.Dynamic reg92 = ctx.addServlet("ManageStudyServlet", ManageStudyServlet.class);
            reg92.addMapping("/ManageStudy", "/ManageStudy1");

            ServletRegistration.Dynamic reg93 = ctx.addServlet("MarkEventCRFCompleteServlet", MarkEventCRFCompleteServlet.class);
            reg93.addMapping("/MarkEventCRFComplete");

            ServletRegistration.Dynamic reg94 = ctx.addServlet("MatchPasswordServlet", MatchPasswordServlet.class);
            reg94.addMapping("/MatchPassword");

            ServletRegistration.Dynamic reg95 = ctx.addServlet("ParticipantFormServlet", ParticipantFormServlet.class);
            reg95.addMapping("/ParticipantFormServlet");

            ServletRegistration.Dynamic reg96 = ctx.addServlet("PauseJobServlet", PauseJobServlet.class);
            reg96.addMapping("/PauseJob");

            ServletRegistration.Dynamic reg97 = ctx.addServlet("PrintAllEventCRFServlet", PrintAllEventCRFServlet.class);
            reg97.addMapping("/PrintAllEventCRF");

            ServletRegistration.Dynamic reg98 = ctx.addServlet("PrintAllSiteEventCRFServlet", PrintAllSiteEventCRFServlet.class);
            reg98.addMapping("/PrintAllSiteEventCRF");

            ServletRegistration.Dynamic reg99 = ctx.addServlet("PrintCRFByIdServlet", PrintCRFByIdServlet.class);
            reg99.addMapping("/PrintCRFById");

            ServletRegistration.Dynamic reg100 = ctx.addServlet("PrintCRFOldServlet", PrintCRFServlet.class);
            reg100.addMapping("/PrintCRFOld");

            ServletRegistration.Dynamic reg101 = ctx.addServlet("PrintCRFServlet", PrintCRFServlet.class);
            reg101.addMapping("/PrintCRF");

            ServletRegistration.Dynamic reg102 = ctx.addServlet("PrintDataEntryServlet", PrintDataEntryServlet.class);
            reg102.addMapping("/PrintDataEntry");

            ServletRegistration.Dynamic reg103 = ctx.addServlet("PrintEventCRFServlet", PrintEventCRFServlet.class);
            reg103.addMapping("/PrintEventCRF");

            ServletRegistration.Dynamic reg104 = ctx.addServlet("PrintoutCertificateServlet", PrintoutCertificateServlet.class);
            reg104.addMapping("/PrintoutCertificate");

            ServletRegistration.Dynamic reg105 = ctx.addServlet("ReassignStudySubjectServlet", ReassignStudySubjectServlet.class);
            reg105.addMapping("/ReassignStudySubject");

            ServletRegistration.Dynamic reg106 = ctx.addServlet("RemoveCRFFromDefinitionServlet", RemoveCRFFromDefinitionServlet.class);
            reg106.addMapping("/RemoveCRFFromDefinition");

            ServletRegistration.Dynamic reg107 = ctx.addServlet("RemoveCRFServlet", RemoveCRFServlet.class);
            reg107.addMapping("/RemoveCRF");

            ServletRegistration.Dynamic reg108 = ctx.addServlet("RemoveCRFVersionServlet", RemoveCRFVersionServlet.class);
            reg108.addMapping("/RemoveCRFVersion");

            ServletRegistration.Dynamic reg109 = ctx.addServlet("RemoveDatasetServlet", RemoveDatasetServlet.class);
            reg109.addMapping("/RemoveDataset");

            ServletRegistration.Dynamic reg110 = ctx.addServlet("RemoveEventCRFServlet", RemoveEventCRFServlet.class);
            reg110.addMapping("/RemoveEventCRF");

            ServletRegistration.Dynamic reg111 = ctx.addServlet("RemoveEventDefinitionServlet", RemoveEventDefinitionServlet.class);
            reg111.addMapping("/RemoveEventDefinition");

            ServletRegistration.Dynamic reg112 = ctx.addServlet("RemoveFilterServlet", RemoveFilterServlet.class);
            reg112.addMapping("/RemoveFilter");

            ServletRegistration.Dynamic reg113 = ctx.addServlet("RemoveRuleSetServlet", RemoveRuleSetServlet.class);
            reg113.addMapping("/RemoveRuleSet");

            ServletRegistration.Dynamic reg114 = ctx.addServlet("RemoveSiteServlet", RemoveSiteServlet.class);
            reg114.addMapping("/RemoveSite");

            ServletRegistration.Dynamic reg115 = ctx.addServlet("RemoveStudyEventServlet", RemoveStudyEventServlet.class);
            reg115.addMapping("/RemoveStudyEvent");

            ServletRegistration.Dynamic reg116 = ctx.addServlet("RemoveStudyServlet", RemoveStudyServlet.class);
            reg116.addMapping("/RemoveStudy");

            ServletRegistration.Dynamic reg117 = ctx.addServlet("RemoveStudySubjectServlet", RemoveStudySubjectServlet.class);
            reg117.addMapping("/RemoveStudySubject");

            ServletRegistration.Dynamic reg118 = ctx.addServlet("RemoveStudyUserRoleServlet", RemoveStudyUserRoleServlet.class);
            reg118.addMapping("/RemoveStudyUserRole");

            ServletRegistration.Dynamic reg119 = ctx.addServlet("RemoveSubjectGroupClassServlet", RemoveSubjectGroupClassServlet.class);
            reg119.addMapping("/RemoveSubjectGroupClass");

            ServletRegistration.Dynamic reg120 = ctx.addServlet("RemoveSubjectServlet", RemoveSubjectServlet.class);
            reg120.addMapping("/RemoveSubject");

            ServletRegistration.Dynamic reg121 = ctx.addServlet("RequestAccountServlet", RequestAccountServlet.class);
            reg121.addMapping("/RequestAccount");

            ServletRegistration.Dynamic reg122 = ctx.addServlet("RequestPasswordServlet", RequestPasswordServlet.class);
            reg122.addMapping("/RequestPassword");

            ServletRegistration.Dynamic reg123 = ctx.addServlet("RequestStudyServlet", RequestStudyServlet.class);
            reg123.addMapping("/RequestStudy");

            ServletRegistration.Dynamic reg124 = ctx.addServlet("ResetPasswordServlet", ResetPasswordServlet.class);
            reg124.addMapping("/ResetPassword");

            ServletRegistration.Dynamic reg125 = ctx.addServlet("ResolveDiscrepancyServlet", ResolveDiscrepancyServlet.class);
            reg125.addMapping("/ResolveDiscrepancy");

            ServletRegistration.Dynamic reg126 = ctx.addServlet("RestoreCRFFromDefinitionServlet", RestoreCRFFromDefinitionServlet.class);
            reg126.addMapping("/RestoreCRFFromDefinition");

            ServletRegistration.Dynamic reg127 = ctx.addServlet("RestoreCRFServlet", RestoreCRFServlet.class);
            reg127.addMapping("/RestoreCRF");

            ServletRegistration.Dynamic reg128 = ctx.addServlet("RestoreCRFVersionServlet", RestoreCRFVersionServlet.class);
            reg128.addMapping("/RestoreCRFVersion");

            ServletRegistration.Dynamic reg129 = ctx.addServlet("RestoreDatasetServlet", RestoreDatasetServlet.class);
            reg129.addMapping("/RestoreDataset");

            ServletRegistration.Dynamic reg130 = ctx.addServlet("RestoreEventCRFServlet", RestoreEventCRFServlet.class);
            reg130.addMapping("/RestoreEventCRF");

            ServletRegistration.Dynamic reg131 = ctx.addServlet("RestoreEventDefinitionServlet", RestoreEventDefinitionServlet.class);
            reg131.addMapping("/RestoreEventDefinition");

            ServletRegistration.Dynamic reg132 = ctx.addServlet("RestoreRuleSetServlet", RestoreRuleSetServlet.class);
            reg132.addMapping("/RestoreRuleSet");

            ServletRegistration.Dynamic reg133 = ctx.addServlet("RestoreSiteServlet", RestoreSiteServlet.class);
            reg133.addMapping("/RestoreSite");

            ServletRegistration.Dynamic reg134 = ctx.addServlet("RestoreStudyEventServlet", RestoreStudyEventServlet.class);
            reg134.addMapping("/RestoreStudyEvent");

            ServletRegistration.Dynamic reg135 = ctx.addServlet("RestoreStudyServlet", RestoreStudyServlet.class);
            reg135.addMapping("/RestoreStudy");

            ServletRegistration.Dynamic reg136 = ctx.addServlet("RestoreStudySubjectServlet", RestoreStudySubjectServlet.class);
            reg136.addMapping("/RestoreStudySubject");

            ServletRegistration.Dynamic reg137 = ctx.addServlet("RestoreStudyUserRoleServlet", RestoreStudyUserRoleServlet.class);
            reg137.addMapping("/RestoreStudyUserRole");

            ServletRegistration.Dynamic reg138 = ctx.addServlet("RestoreSubjectGroupClassServlet", RestoreSubjectGroupClassServlet.class);
            reg138.addMapping("/RestoreSubjectGroupClass");

            ServletRegistration.Dynamic reg139 = ctx.addServlet("RestoreSubjectServlet", RestoreSubjectServlet.class);
            reg139.addMapping("/RestoreSubject");

            ServletRegistration.Dynamic reg140 = ctx.addServlet("RunRuleServlet", RunRuleServlet.class);
            reg140.addMapping("/RunRule");

            ServletRegistration.Dynamic reg141 = ctx.addServlet("RunRuleSetServlet", RunRuleSetServlet.class);
            reg141.addMapping("/RunRuleSet");

            ServletRegistration.Dynamic reg142 = ctx.addServlet("SQLInitServlet", SQLInitServlet.class);
            reg142.setLoadOnStartup(1);

            ServletRegistration.Dynamic reg143 = ctx.addServlet("SelectItemsServlet", SelectItemsServlet.class);
            reg143.addMapping("/SelectItems");

            ServletRegistration.Dynamic reg144 = ctx.addServlet("SendTestEmailServlet", SendTestEmailServlet.class);
            reg144.addMapping("/SendTestEmail");

            ServletRegistration.Dynamic reg145 = ctx.addServlet("SetStudyUserRoleServlet", SetStudyUserRoleServlet.class);
            reg145.addMapping("/SetStudyUserRole");

            ServletRegistration.Dynamic reg146 = ctx.addServlet("SetUserRoleServlet", SetUserRoleServlet.class);
            reg146.addMapping("/SetUserRole");

            ServletRegistration.Dynamic reg147 = ctx.addServlet("ShowFileServlet", ShowFileServlet.class);
            reg147.addMapping("/ShowFile");

            ServletRegistration.Dynamic reg148 = ctx.addServlet("SignStudySubjectServlet", SignStudySubjectServlet.class);
            reg148.addMapping("/SignStudySubject");

            ServletRegistration.Dynamic reg149 = ctx.addServlet("StudyAuditLogDataServlet", StudyAuditLogDataServlet.class);
            reg149.addMapping("/StudyAuditLogData");

            ServletRegistration.Dynamic reg150 = ctx.addServlet("StudyAuditLogServlet", StudyAuditLogServlet.class);
            reg150.addMapping("/StudyAuditLog");

            ServletRegistration.Dynamic reg151 = ctx.addServlet("SubmitDataServlet", SubmitDataServlet.class);
            reg151.addMapping("/SubmitData");

            ServletRegistration.Dynamic reg152 = ctx.addServlet("SystemStatusServlet", SystemStatusServlet.class);
            reg152.addMapping("/SystemStatus");

            ServletRegistration.Dynamic reg153 = ctx.addServlet("TableOfContentsServlet", TableOfContentsServlet.class);
            reg153.addMapping("/TableOfContents");

            ServletRegistration.Dynamic reg154 = ctx.addServlet("TechAdminServlet", TechAdminServlet.class);
            reg154.addMapping("/TechAdmin");

            ServletRegistration.Dynamic reg155 = ctx.addServlet("TestRuleServlet", TestRuleServlet.class);
            reg155.addMapping("/TestRule");

            ServletRegistration.Dynamic reg156 = ctx.addServlet("UnLockUserServlet", UnLockUserServlet.class);
            reg156.addMapping("/UnLockUser");

            ServletRegistration.Dynamic reg157 = ctx.addServlet("UnlockCRFVersionServlet", UnlockCRFVersionServlet.class);
            reg157.addMapping("/UnlockCRFVersion");

            ServletRegistration.Dynamic reg158 = ctx.addServlet("UnlockEventDefinitionServlet", UnlockEventDefinitionServlet.class);
            reg158.addMapping("/UnlockEventDefinition");

            ServletRegistration.Dynamic reg159 = ctx.addServlet("UpdateCRFServlet", UpdateCRFServlet.class);
            reg159.addMapping("/UpdateCRF");

            ServletRegistration.Dynamic reg160 = ctx.addServlet("UpdateEventDefinitionServlet", UpdateEventDefinitionServlet.class);
            reg160.addMapping("/UpdateEventDefinition");

            ServletRegistration.Dynamic reg161 = ctx.addServlet("UpdateJobExportServlet", UpdateJobExportServlet.class);
            reg161.addMapping("/UpdateJobExport");

            ServletRegistration.Dynamic reg162 = ctx.addServlet("UpdateJobImportServlet", UpdateJobImportServlet.class);
            reg162.addMapping("/UpdateJobImport");

            ServletRegistration.Dynamic reg163 = ctx.addServlet("UpdateProfileServlet", UpdateProfileServlet.class);
            reg163.addMapping("/UpdateProfile");

            ServletRegistration.Dynamic reg164 = ctx.addServlet("UpdateRuleSetRuleServlet", UpdateRuleSetRuleServlet.class);
            reg164.addMapping("/UpdateRuleSetRule");

            ServletRegistration.Dynamic reg165 = ctx.addServlet("UpdateStudyEventServlet", UpdateStudyEventServlet.class);
            reg165.addMapping("/UpdateStudyEvent");

            ServletRegistration.Dynamic reg166 = ctx.addServlet("UpdateStudyServlet", UpdateStudyServlet.class);
            reg166.addMapping("/UpdateStudy");

            ServletRegistration.Dynamic reg167 = ctx.addServlet("UpdateStudyServletNew", UpdateStudyServletNew.class);
            reg167.addMapping("/UpdateStudyNew");

            ServletRegistration.Dynamic reg168 = ctx.addServlet("UpdateStudySubjectServlet", UpdateStudySubjectServlet.class);
            reg168.addMapping("/UpdateStudySubject");

            ServletRegistration.Dynamic reg169 = ctx.addServlet("UpdateSubStudyServlet", UpdateSubStudyServlet.class);
            reg169.addMapping("/UpdateSubStudy");

            ServletRegistration.Dynamic reg170 = ctx.addServlet("UpdateSubjectGroupClassServlet", UpdateSubjectGroupClassServlet.class);
            reg170.addMapping("/UpdateSubjectGroupClass");

            ServletRegistration.Dynamic reg171 = ctx.addServlet("UpdateSubjectServlet", UpdateSubjectServlet.class);
            reg171.addMapping("/UpdateSubject");

            ServletRegistration.Dynamic reg172 = ctx.addServlet("UploadFileServlet", UploadFileServlet.class);
            reg172.addMapping("/UploadFile");

            ServletRegistration.Dynamic reg173 = ctx.addServlet("VerifyImportedCRFDataServlet", VerifyImportedCRFDataServlet.class);
            reg173.addMapping("/VerifyImportedCRFData");

            ServletRegistration.Dynamic reg174 = ctx.addServlet("VerifyImportedRuleServlet", VerifyImportedRuleServlet.class);
            reg174.addMapping("/VerifyImportedRule");

            ServletRegistration.Dynamic reg175 = ctx.addServlet("ViewAllJobsServlet", ViewAllJobsServlet.class);
            reg175.addMapping("/ViewAllJobs");

            ServletRegistration.Dynamic reg176 = ctx.addServlet("ViewCRFServlet", ViewCRFServlet.class);
            reg176.addMapping("/ViewCRF");

            ServletRegistration.Dynamic reg177 = ctx.addServlet("ViewCRFVersionPreview", ViewCRFVersionPreview.class);
            reg177.addMapping("/ViewCRFVersionPreview");

            ServletRegistration.Dynamic reg178 = ctx.addServlet("ViewCRFVersionServlet", ViewCRFVersionServlet.class);
            reg178.addMapping("/ViewCRFVersion");

            ServletRegistration.Dynamic reg179 = ctx.addServlet("ViewDatasetsServlet", ViewDatasetsServlet.class);
            reg179.addMapping("/ViewDatasets");

            ServletRegistration.Dynamic reg180 = ctx.addServlet("ViewDiscrepancyNoteServlet", ViewDiscrepancyNoteServlet.class);
            reg180.addMapping("/ViewDiscrepancyNote");

            ServletRegistration.Dynamic reg181 = ctx.addServlet("ViewEventCRFContentServlet", ViewEventCRFContentServlet.class);
            reg181.addMapping("/ViewEventCRFContent");

            ServletRegistration.Dynamic reg182 = ctx.addServlet("ViewEventCRFServlet", ViewEventCRFServlet.class);
            reg182.addMapping("/ViewEventCRF");

            ServletRegistration.Dynamic reg183 = ctx.addServlet("ViewEventDefinitionReadOnlyServlet", ViewEventDefinitionReadOnlyServlet.class);
            reg183.addMapping("/ViewEventDefinitionReadOnly");

            ServletRegistration.Dynamic reg184 = ctx.addServlet("ViewEventDefinitionServlet", ViewEventDefinitionServlet.class);
            reg184.addMapping("/ViewEventDefinition");

            ServletRegistration.Dynamic reg185 = ctx.addServlet("ViewImportJobServlet", ViewImportJobServlet.class);
            reg185.addMapping("/ViewImportJob");

            ServletRegistration.Dynamic reg186 = ctx.addServlet("ViewItemAuditLogServlet", ViewItemAuditLogServlet.class);
            reg186.addMapping("/ViewItemAuditLog");

            ServletRegistration.Dynamic reg187 = ctx.addServlet("ViewItemDetailServlet", ViewItemDetailServlet.class);
            reg187.addMapping("/ViewItemDetail");

            ServletRegistration.Dynamic reg188 = ctx.addServlet("ViewJobServlet", ViewJobServlet.class);
            reg188.addMapping("/ViewJob");

            ServletRegistration.Dynamic reg189 = ctx.addServlet("ViewLogMessage", ViewLogMessageServlet.class);
            reg189.addMapping("/ViewLogMessage");

            ServletRegistration.Dynamic reg190 = ctx.addServlet("ViewNoteServlet", ViewNoteServlet.class);
            reg190.addMapping("/ViewNote");

            ServletRegistration.Dynamic reg191 = ctx.addServlet("ViewNotesDataServlet", ViewNotesDataServlet.class);
            reg191.addMapping("/ViewNotesData");

            ServletRegistration.Dynamic reg192 = ctx.addServlet("ViewNotesServlet", ViewNotesServlet.class);
            reg192.addMapping("/ViewNotes");

            ServletRegistration.Dynamic reg193 = ctx.addServlet("ViewRuleAssignmentDataServlet", ViewRuleAssignmentDataServlet.class);
            reg193.addMapping("/ViewRuleAssignmentData");

            ServletRegistration.Dynamic reg194 = ctx.addServlet("ViewRuleAssignmentNewServlet", ViewRuleAssignmentNewServlet.class);
            reg194.addMapping("/ViewRuleAssignment");

            ServletRegistration.Dynamic reg195 = ctx.addServlet("ViewRuleAssignmentServlet", ViewRuleAssignmentServlet.class);
            reg195.addMapping("/ViewRuleAssignmentNew");

            ServletRegistration.Dynamic reg196 = ctx.addServlet("ViewRuleSetAuditServlet", ViewRuleSetAuditServlet.class);
            reg196.addMapping("/ViewRuleSetAudit");

            ServletRegistration.Dynamic reg197 = ctx.addServlet("ViewRuleSetServlet", ViewRuleSetServlet.class);
            reg197.addMapping("/ViewRuleSet");

            ServletRegistration.Dynamic reg198 = ctx.addServlet("ViewSchedulerServlet", ViewSchedulerServlet.class);
            reg198.addMapping("/ViewScheduler");

            ServletRegistration.Dynamic reg199 = ctx.addServlet("ViewSectionDataEntryByIdServlet", ViewSectionDataEntryByIdServlet.class);
            reg199.addMapping("/ViewSectionDataEntryById");

            ServletRegistration.Dynamic reg200 = ctx.addServlet("ViewSectionDataEntryPreview", ViewSectionDataEntryPreview.class);
            reg200.addMapping("/SectionPreview");

            ServletRegistration.Dynamic reg201 = ctx.addServlet("ViewSectionDataEntryRESTUrlServlet", ViewSectionDataEntryRESTUrlServlet.class);
            reg201.addMapping("/ViewSectionDataEntryRESTUrlServlet");

            ServletRegistration.Dynamic reg202 = ctx.addServlet("ViewSectionDataEntryServlet", ViewSectionDataEntryServlet.class);
            reg202.addMapping("/ViewSectionDataEntry");

            ServletRegistration.Dynamic reg203 = ctx.addServlet("ViewSelectedServlet", ViewSelectedServlet.class);
            reg203.addMapping("/ViewSelected");

            ServletRegistration.Dynamic reg204 = ctx.addServlet("ViewSingleJobServlet", ViewSingleJobServlet.class);
            reg204.addMapping("/ViewSingleJob");

            ServletRegistration.Dynamic reg205 = ctx.addServlet("ViewSiteServlet", ViewSiteServlet.class);
            reg205.addMapping("/ViewSite");

            ServletRegistration.Dynamic reg206 = ctx.addServlet("ViewStudyEventsServlet", ViewStudyEventsServlet.class);
            reg206.addMapping("/ViewStudyEvents");

            ServletRegistration.Dynamic reg207 = ctx.addServlet("ViewStudyServlet", ViewStudyServlet.class);
            reg207.addMapping("/ViewStudy");

            ServletRegistration.Dynamic reg208 = ctx.addServlet("ViewStudySubjectAuditLogServlet", ViewStudySubjectAuditLogServlet.class);
            reg208.addMapping("/ViewStudySubjectAuditLog");

            ServletRegistration.Dynamic reg209 = ctx.addServlet("ViewStudySubjectServlet", ViewStudySubjectServlet.class);
            reg209.addMapping("/ViewStudySubject");

            ServletRegistration.Dynamic reg210 = ctx.addServlet("ViewStudyUserServlet", ViewStudyUserServlet.class);
            reg210.addMapping("/ViewStudyUser");

            ServletRegistration.Dynamic reg211 = ctx.addServlet("ViewSubjectGroupClassServlet", ViewSubjectGroupClassServlet.class);
            reg211.addMapping("/ViewSubjectGroupClass");

            ServletRegistration.Dynamic reg212 = ctx.addServlet("ViewSubjectServlet", ViewSubjectServlet.class);
            reg212.addMapping("/ViewSubject");

            ServletRegistration.Dynamic reg213 = ctx.addServlet("ViewTableOfContentServlet", ViewTableOfContentServlet.class);
            reg213.addMapping("/ViewTableOfContent");

            ServletRegistration.Dynamic reg214 = ctx.addServlet("ViewUserAccountServlet", ViewUserAccountServlet.class);
            reg214.addMapping("/ViewUserAccount");

        };
    }
}