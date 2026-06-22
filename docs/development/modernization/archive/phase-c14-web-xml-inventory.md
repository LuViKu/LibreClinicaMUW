# Phase C.14 — web.xml inventory


Generated 2026-05-30. **222 servlets**, 7 filters, 5 listeners across 2344 lines of `web/src/main/webapp/WEB-INF/web.xml`.
This document is the migration matrix the C.14 cliff push works against.

---

## Listeners (5)

| Class | Migration target |
|---|---|
| `org.springframework.web.context.ContextLoaderListener` (subclassed by `OCContextLoaderListener`) | **Drop the listener entry** — `SpringBootServletInitializer` provides the root context instead. Hoist `OCContextLoaderListener`'s MDC + hostname setup into a `@PostConstruct` on a Boot-managed `@Component`. |
| `org.springframework.security.web.session.HttpSessionEventPublisher` | **Keep** — register as `@Bean ServletListenerRegistrationBean<HttpSessionEventPublisher>`. Required by Spring Security concurrent-session control. |
| `at.ac.meduniwien.ophthalmology.libreclinica.control.OCServletContextListener` | **Convert** — register as `@Bean ServletListenerRegistrationBean`. Reads OC version + logs usage stats on startup. |
| `org.springframework.web.context.request.RequestContextListener` | **Drop** — Boot WebMvc autoconfig provides equivalent request-scoped bean wiring. |
| (commented out) `net.sf.ehcache.constructs.web.ShutdownListener` | **N/A** — already retired with ehcache 2 in Phase B.5. |

---

## Filters (7)

| Filter name | Class | Migration target |
|---|---|---|
| `restODMFilter` | `RestODMFilter` | `FilterRegistrationBean<RestODMFilter>` ordered first |
| `encodingFilter` | `CharacterEncodingFilter` (Spring) | Replaced by Boot `HttpEncodingAutoConfiguration` via `spring.servlet.encoding.charset=UTF-8` in application.yml |
| `localeFilter` | `LocaleFilter` | `FilterRegistrationBean<LocaleFilter>` |
| `springSecurityFilterChain` | `DelegatingFilterProxy` | Provided automatically by Boot `SecurityFilterAutoConfiguration` once `SecurityFilterChain` is a `@Bean` (C.10 work) |
| `hibernateFilter` | `OpenEntityManagerInViewFilter` | Flip `spring.jpa.open-in-view: true` in application.yml (currently `false`) — or add an explicit `@Bean FilterRegistrationBean<OpenEntityManagerInViewFilter>` |
| `logFilter` | `OCServletFilter` | `FilterRegistrationBean<OCServletFilter>` |
| `apiSecurityFilter` | `DelegatingFilterProxy` (delegates to `apiSecurityFilter` bean from `applicationContext-core-security.xml`) | `FilterRegistrationBean<DelegatingFilterProxy>` with same target bean name |

---

## Servlets by package (222 total)

| Package | Count | Migration strategy |
|---|---|---|
| `at.ac.meduniwien.ophthalmology.libreclinica.control` | 1 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `at.ac.meduniwien.ophthalmology.libreclinica.control.admin` | 56 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `at.ac.meduniwien.ophthalmology.libreclinica.control.extract` | 21 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `at.ac.meduniwien.ophthalmology.libreclinica.control.login` | 9 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy` | 86 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `at.ac.meduniwien.ophthalmology.libreclinica.control.rule` | 1 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `at.ac.meduniwien.ophthalmology.libreclinica.control.submit` | 38 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `at.ac.meduniwien.ophthalmology.libreclinica.control.techadmin` | 2 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `at.ac.meduniwien.ophthalmology.libreclinica.control.urlRewrite` | 1 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `at.ac.meduniwien.ophthalmology.libreclinica.view.form` | 1 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `at.ac.meduniwien.ophthalmology.libreclinica.web` | 1 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `at.ac.meduniwien.ophthalmology.libreclinica.web.openrosa` | 1 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `com.sun.jersey.spi.spring.container.servlet` | 2 | Jersey JAX-RS — verify usage; if dead, delete entries + drop jersey deps from poms |
| `org.springframework.web.servlet` | 1 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |
| `org.springframework.ws.transport.http` | 1 | Bulk-register via `LegacyServletRegistry` @Configuration; see below |

### `at.ac.meduniwien.ophthalmology.libreclinica.control` (1 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `MainMenuServlet` | `MainMenuServlet` | `/MainMenu` | - |

### `at.ac.meduniwien.ophthalmology.libreclinica.control.admin` (56 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `AdminSystemServlet` | `AdminSystemServlet` | `/AdminSystem` | - |
| `AuditDatabaseServlet` | `AuditDatabaseServlet` | `/AuditDatabase` | - |
| `AuditLogStudyServlet` | `AuditLogStudyServlet` | `/AuditLogStudy` | - |
| `AuditLogUserServlet` | `AuditLogUserServlet` | `/AuditLogUser` | - |
| `AuditUserActivityDataServlet` | `AuditUserActivityDataServlet` | `/AuditUserActivityData` | - |
| `AuditUserActivityServlet` | `AuditUserActivityServlet` | `/AuditUserActivity` | - |
| `BatchCRFMigrationServlet` | `BatchCRFMigrationServlet` | `/BatchCRFMigration` | - |
| `ConfigurePasswordRequirementsServlet` | `ConfigurePasswordRequirementsServlet` | `/ConfigurePasswordRequirements` | - |
| `ConfigureServlet` | `ConfigureServlet` | `/Configure` | - |
| `CreateCRFServlet` | `CreateCRFServlet` | `/CreateCRF` | - |
| `CreateCRFVersionServlet` | `CreateCRFVersionServlet` | `/CreateCRFVersion` | - |
| `CreateJobExportServlet` | `CreateJobExportServlet` | `/CreateJobExport` | - |
| `CreateJobImportServlet` | `CreateJobImportServlet` | `/CreateJobImport` | - |
| `CreateUserAccountServlet` | `CreateUserAccountServlet` | `/CreateUserAccount` | - |
| `CreateXformCRFVersionServlet` | `CreateXformCRFVersionServlet` | `/CreateXformCRFVersion` | - |
| `DeleteCRFVersionServlet` | `DeleteCRFVersionServlet` | `/DeleteCRFVersion` | - |
| `DeleteEventCRFServlet` | `DeleteEventCRFServlet` | `/DeleteEventCRF` | - |
| `DeleteStudyUserRoleServlet` | `DeleteStudyUserRoleServlet` | `/DeleteStudyUserRole` | - |
| `DeleteUserServlet` | `DeleteUserServlet` | `/DeleteUser` | - |
| `DownloadStudyMetadataServlet` | `DownloadStudyMetadataServlet` | `/DownloadStudyMetadata` | - |
| `DownloadVersionSpreadSheetServlet` | `DownloadVersionSpreadSheetServlet` | `/DownloadVersionSpreadSheet` | - |
| `EditStudyUserRoleServlet` | `EditStudyUserRoleServlet` | `/EditStudyUserRole` | - |
| `EditUserAccountServlet` | `EditUserAccountServlet` | `/EditUserAccount` | - |
| `InitCreateCRFVersionServlet` | `InitCreateCRFVersionServlet` | `/InitCreateCRFVersion` | - |
| `InitUpdateCRFServlet` | `InitUpdateCRFServlet` | `/InitUpdateCRF` | - |
| `ListCRFServlet` | `ListCRFServlet` | `/ListCRF` | - |
| `ListSubjectDataServlet` | `ListSubjectDataServlet` | `/ListSubjectData` | - |
| `ListSubjectServlet` | `ListSubjectServlet` | `/ListSubject` | - |
| `ListUserAccountsServlet` | `ListUserAccountsServlet` | `/ListUserAccounts` | - |
| `PauseJobServlet` | `PauseJobServlet` | `/PauseJob` | - |
| `PrintoutCertificateServlet` | `PrintoutCertificateServlet` | `/PrintoutCertificate` | - |
| `RemoveCRFServlet` | `RemoveCRFServlet` | `/RemoveCRF` | - |
| `RemoveCRFVersionServlet` | `RemoveCRFVersionServlet` | `/RemoveCRFVersion` | - |
| `RemoveStudyServlet` | `RemoveStudyServlet` | `/RemoveStudy` | - |
| `RemoveSubjectServlet` | `RemoveSubjectServlet` | `/RemoveSubject` | - |
| `RestoreCRFServlet` | `RestoreCRFServlet` | `/RestoreCRF` | - |
| `RestoreCRFVersionServlet` | `RestoreCRFVersionServlet` | `/RestoreCRFVersion` | - |
| `RestoreStudyServlet` | `RestoreStudyServlet` | `/RestoreStudy` | - |
| `RestoreSubjectServlet` | `RestoreSubjectServlet` | `/RestoreSubject` | - |
| `SendTestEmailServlet` | `SendTestEmailServlet` | `/SendTestEmail` | - |
| `SetUserRoleServlet` | `SetUserRoleServlet` | `/SetUserRole` | - |
| `SystemStatusServlet` | `SystemStatusServlet` | `/SystemStatus` | - |
| `UnLockUserServlet` | `UnLockUserServlet` | `/UnLockUser` | - |
| `UpdateCRFServlet` | `UpdateCRFServlet` | `/UpdateCRF` | - |
| `UpdateJobExportServlet` | `UpdateJobExportServlet` | `/UpdateJobExport` | - |
| `UpdateJobImportServlet` | `UpdateJobImportServlet` | `/UpdateJobImport` | - |
| `UpdateSubjectServlet` | `UpdateSubjectServlet` | `/UpdateSubject` | - |
| `ViewAllJobsServlet` | `ViewAllJobsServlet` | `/ViewAllJobs` | - |
| `ViewCRFServlet` | `ViewCRFServlet` | `/ViewCRF` | - |
| `ViewImportJobServlet` | `ViewImportJobServlet` | `/ViewImportJob` | - |
| `ViewJobServlet` | `ViewJobServlet` | `/ViewJob` | - |
| `ViewLogMessage` | `ViewLogMessageServlet` | `/ViewLogMessage` | - |
| `ViewSingleJobServlet` | `ViewSingleJobServlet` | `/ViewSingleJob` | - |
| `ViewStudyServlet` | `ViewStudyServlet` | `/ViewStudy` | - |
| `ViewSubjectServlet` | `ViewSubjectServlet` | `/ViewSubject` | - |
| `ViewUserAccountServlet` | `ViewUserAccountServlet` | `/ViewUserAccount` | - |

### `at.ac.meduniwien.ophthalmology.libreclinica.control.extract` (21 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `AccessFileServlet` | `AccessFileServlet` | `/AccessFile` | - |
| `ApplyFilterServlet` | `ApplyFilterServlet` | `/ApplyFilter` | - |
| `ChooseDownloadFormat` | `ChooseDownloadFormat` | `/ChooseDownloadFormat` | - |
| `CreateDatasetServlet` | `CreateDatasetServlet` | `/CreateDataset` | - |
| `CreateFiltersOneServlet` | `CreateFiltersOneServlet` | `/CreateFiltersOne` | - |
| `CreateFiltersThreeServlet` | `CreateFiltersThreeServlet` | `/CreateFiltersThree` | - |
| `CreateFiltersTwoServlet` | `CreateFiltersTwoServlet` | `/CreateFiltersTwo` | - |
| `DiscrepancyNoteOutputServlet` | `DiscrepancyNoteOutputServlet` | `/DiscrepancyNoteOutputServlet` | - |
| `EditDatasetServlet` | `EditDatasetServlet` | `/EditDataset` | - |
| `EditFilterServlet` | `EditFilterServlet` | `/EditFilter` | - |
| `EditSelectedServlet` | `EditSelectedServlet` | `/EditSelected` | - |
| `ExportDatasetServlet` | `ExportDatasetServlet` | `/ExportDataset` | - |
| `ExtractDatasetsMainServlet` | `ExtractDatasetsMainServlet` | `/ExtractDatasetsMain` | - |
| `RemoveDatasetServlet` | `RemoveDatasetServlet` | `/RemoveDataset` | - |
| `RemoveFilterServlet` | `RemoveFilterServlet` | `/RemoveFilter` | - |
| `RestoreDatasetServlet` | `RestoreDatasetServlet` | `/RestoreDataset` | - |
| `SelectItemsServlet` | `SelectItemsServlet` | `/SelectItems` | - |
| `ShowFileServlet` | `ShowFileServlet` | `/ShowFile` | - |
| `ViewDatasetsServlet` | `ViewDatasetsServlet` | `/ViewDatasets` | - |
| `ViewItemDetailServlet` | `ViewItemDetailServlet` | `/ViewItemDetail` | - |
| `ViewSelectedServlet` | `ViewSelectedServlet` | `/ViewSelected` | - |

### `at.ac.meduniwien.ophthalmology.libreclinica.control.login` (9 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `ChangeStudyServlet` | `ChangeStudyServlet` | `/ChangeStudy` | - |
| `ContactServlet` | `ContactServlet` | `/Contact` | - |
| `EnterpriseServlet` | `EnterpriseServlet` | `/Enterprise` | - |
| `LogoutServlet` | `LogoutServlet` | `/Logout` | - |
| `RequestAccountServlet` | `RequestAccountServlet` | `/RequestAccount` | - |
| `RequestPasswordServlet` | `RequestPasswordServlet` | `/RequestPassword` | - |
| `RequestStudyServlet` | `RequestStudyServlet` | `/RequestStudy` | - |
| `ResetPasswordServlet` | `ResetPasswordServlet` | `/ResetPassword` | - |
| `UpdateProfileServlet` | `UpdateProfileServlet` | `/UpdateProfile` | - |

### `at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy` (86 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `AddCRFToDefinitionServlet` | `AddCRFToDefinitionServlet` | `/AddCRFToDefinition` | - |
| `AssignUserToStudyServlet` | `AssignUserToStudyServlet` | `/AssignUserToStudy` | - |
| `ChangeDefinitionCRFOrdinalServlet` | `ChangeDefinitionCRFOrdinalServlet` | `/ChangeDefinitionCRFOrdinal` | - |
| `ChangeDefinitionOrdinalServlet` | `ChangeDefinitionOrdinalServlet` | `/ChangeDefinitionOrdinal` | - |
| `CreateStudyServlet` | `CreateStudyServlet` | `/CreateStudy` | - |
| `CreateSubStudyServlet` | `CreateSubStudyServlet` | `/CreateSubStudy` | - |
| `CreateSubjectGroupClassServlet` | `CreateSubjectGroupClassServlet` | `/CreateSubjectGroupClass` | - |
| `DefineStudyEventServlet` | `DefineStudyEventServlet` | `/DefineStudyEvent` | - |
| `DeleteStudyEventServlet` | `DeleteStudyEventServlet` | `/DeleteStudyEvent` | - |
| `ExportExcelStudySubjectAuditLogServlet` | `ExportExcelStudySubjectAuditLogServlet` | `/ExportExcelStudySubjectAuditLog` | - |
| `InitUpdateEventDefinitionServlet` | `InitUpdateEventDefinitionServlet` | `/InitUpdateEventDefinition` | - |
| `InitUpdateStudyServlet` | `InitUpdateStudyServlet` | `/InitUpdateStudy` | - |
| `InitUpdateSubStudyServlet` | `InitUpdateSubStudyServlet` | `/InitUpdateSubStudy` | - |
| `ListDiscNotesForCRFDataServlet` | `ListDiscNotesForCRFDataServlet` | `/ListDiscNotesForCRFData` | - |
| `ListDiscNotesForCRFServlet` | `ListDiscNotesForCRFServlet` | `/ListDiscNotesForCRFServlet` | - |
| `ListEventDefinitionServlet` | `ListEventDefinitionServlet` | `/ListEventDefinition` | - |
| `ListEventsForSubjectServlet` | `ListEventsForSubjectServlet` | `/ListEventsForSubject` | - |
| `ListEventsForSubjectsDataServlet` | `ListEventsForSubjectsDataServlet` | `/ListEventsForSubjectsData` | - |
| `ListEventsForSubjectsServlet` | `ListEventsForSubjectsServlet` | `/ListEventsForSubjects` | - |
| `ListSiteServlet` | `ListSiteServlet` | `/ListSite` | - |
| `ListStudyServlet` | `ListStudyServlet` | `/ListStudy` | - |
| `ListStudySubjectsManageServlet` | `ListStudySubjectsManageServlet` | `/ListStudySubject` | - |
| `ListStudyUserServlet` | `ListStudyUserServlet` | `/ListStudyUser` | - |
| `ListSubjectGroupClassServlet` | `ListSubjectGroupClassServlet` | `/ListSubjectGroupClass` | - |
| `LockCRFVersionServlet` | `LockCRFVersionServlet` | `/LockCRFVersion` | - |
| `LockEventDefinitionServlet` | `LockEventDefinitionServlet` | `/LockEventDefinition` | - |
| `ManageStudyServlet` | `ManageStudyServlet` | `/ManageStudy`, `/ManageStudy1` | - |
| `PrintAllEventCRFServlet` | `PrintAllEventCRFServlet` | `/PrintAllEventCRF` | - |
| `PrintAllSiteEventCRFServlet` | `PrintAllSiteEventCRFServlet` | `/PrintAllSiteEventCRF` | - |
| `PrintCRFByIdServlet` | `PrintCRFByIdServlet` | `/PrintCRFById` | - |
| `PrintCRFOldServlet` | `PrintCRFServlet` | `/PrintCRFOld` | - |
| `PrintCRFServlet` | `PrintCRFServlet` | `/PrintCRF` | - |
| `PrintDataEntryServlet` | `PrintDataEntryServlet` | `/PrintDataEntry` | - |
| `PrintEventCRFServlet` | `PrintEventCRFServlet` | `/PrintEventCRF` | - |
| `ReassignStudySubjectServlet` | `ReassignStudySubjectServlet` | `/ReassignStudySubject` | - |
| `RemoveCRFFromDefinitionServlet` | `RemoveCRFFromDefinitionServlet` | `/RemoveCRFFromDefinition` | - |
| `RemoveEventCRFServlet` | `RemoveEventCRFServlet` | `/RemoveEventCRF` | - |
| `RemoveEventDefinitionServlet` | `RemoveEventDefinitionServlet` | `/RemoveEventDefinition` | - |
| `RemoveSiteServlet` | `RemoveSiteServlet` | `/RemoveSite` | - |
| `RemoveStudyEventServlet` | `RemoveStudyEventServlet` | `/RemoveStudyEvent` | - |
| `RemoveStudySubjectServlet` | `RemoveStudySubjectServlet` | `/RemoveStudySubject` | - |
| `RemoveStudyUserRoleServlet` | `RemoveStudyUserRoleServlet` | `/RemoveStudyUserRole` | - |
| `RemoveSubjectGroupClassServlet` | `RemoveSubjectGroupClassServlet` | `/RemoveSubjectGroupClass` | - |
| `ResolveDiscrepancyServlet` | `ResolveDiscrepancyServlet` | `/ResolveDiscrepancy` | - |
| `RestoreCRFFromDefinitionServlet` | `RestoreCRFFromDefinitionServlet` | `/RestoreCRFFromDefinition` | - |
| `RestoreEventCRFServlet` | `RestoreEventCRFServlet` | `/RestoreEventCRF` | - |
| `RestoreEventDefinitionServlet` | `RestoreEventDefinitionServlet` | `/RestoreEventDefinition` | - |
| `RestoreSiteServlet` | `RestoreSiteServlet` | `/RestoreSite` | - |
| `RestoreStudyEventServlet` | `RestoreStudyEventServlet` | `/RestoreStudyEvent` | - |
| `RestoreStudySubjectServlet` | `RestoreStudySubjectServlet` | `/RestoreStudySubject` | - |
| `RestoreStudyUserRoleServlet` | `RestoreStudyUserRoleServlet` | `/RestoreStudyUserRole` | - |
| `RestoreSubjectGroupClassServlet` | `RestoreSubjectGroupClassServlet` | `/RestoreSubjectGroupClass` | - |
| `SetStudyUserRoleServlet` | `SetStudyUserRoleServlet` | `/SetStudyUserRole` | - |
| `SignStudySubjectServlet` | `SignStudySubjectServlet` | `/SignStudySubject` | - |
| `StudyAuditLogDataServlet` | `StudyAuditLogDataServlet` | `/StudyAuditLogData` | - |
| `StudyAuditLogServlet` | `StudyAuditLogServlet` | `/StudyAuditLog` | - |
| `UnlockCRFVersionServlet` | `UnlockCRFVersionServlet` | `/UnlockCRFVersion` | - |
| `UnlockEventDefinitionServlet` | `UnlockEventDefinitionServlet` | `/UnlockEventDefinition` | - |
| `UpdateEventDefinitionServlet` | `UpdateEventDefinitionServlet` | `/UpdateEventDefinition` | - |
| `UpdateStudyEventServlet` | `UpdateStudyEventServlet` | `/UpdateStudyEvent` | - |
| `UpdateStudyServlet` | `UpdateStudyServlet` | `/UpdateStudy` | - |
| `UpdateStudyServletNew` | `UpdateStudyServletNew` | `/UpdateStudyNew` | - |
| `UpdateStudySubjectServlet` | `UpdateStudySubjectServlet` | `/UpdateStudySubject` | - |
| `UpdateSubStudyServlet` | `UpdateSubStudyServlet` | `/UpdateSubStudy` | - |
| `UpdateSubjectGroupClassServlet` | `UpdateSubjectGroupClassServlet` | `/UpdateSubjectGroupClass` | - |
| `ViewCRFVersionPreview` | `ViewCRFVersionPreview` | `/ViewCRFVersionPreview` | - |
| `ViewCRFVersionServlet` | `ViewCRFVersionServlet` | `/ViewCRFVersion` | - |
| `ViewEventCRFContentServlet` | `ViewEventCRFContentServlet` | `/ViewEventCRFContent` | - |
| `ViewEventCRFServlet` | `ViewEventCRFServlet` | `/ViewEventCRF` | - |
| `ViewEventDefinitionReadOnlyServlet` | `ViewEventDefinitionReadOnlyServlet` | `/ViewEventDefinitionReadOnly` | - |
| `ViewEventDefinitionServlet` | `ViewEventDefinitionServlet` | `/ViewEventDefinition` | - |
| `ViewItemAuditLogServlet` | `ViewItemAuditLogServlet` | `/ViewItemAuditLog` | - |
| `ViewNoteServlet` | `ViewNoteServlet` | `/ViewNote` | - |
| `ViewNotesDataServlet` | `ViewNotesDataServlet` | `/ViewNotesData` | - |
| `ViewNotesServlet` | `ViewNotesServlet` | `/ViewNotes` | - |
| `ViewSectionDataEntryByIdServlet` | `ViewSectionDataEntryByIdServlet` | `/ViewSectionDataEntryById` | - |
| `ViewSectionDataEntryPreview` | `ViewSectionDataEntryPreview` | `/SectionPreview` | - |
| `ViewSectionDataEntryRESTUrlServlet` | `ViewSectionDataEntryRESTUrlServlet` | `/ViewSectionDataEntryRESTUrlServlet` | - |
| `ViewSectionDataEntryServlet` | `ViewSectionDataEntryServlet` | `/ViewSectionDataEntry` | - |
| `ViewSiteServlet` | `ViewSiteServlet` | `/ViewSite` | - |
| `ViewStudyEventsServlet` | `ViewStudyEventsServlet` | `/ViewStudyEvents` | - |
| `ViewStudySubjectAuditLogServlet` | `ViewStudySubjectAuditLogServlet` | `/ViewStudySubjectAuditLog` | - |
| `ViewStudySubjectServlet` | `ViewStudySubjectServlet` | `/ViewStudySubject` | - |
| `ViewStudyUserServlet` | `ViewStudyUserServlet` | `/ViewStudyUser` | - |
| `ViewSubjectGroupClassServlet` | `ViewSubjectGroupClassServlet` | `/ViewSubjectGroupClass` | - |
| `ViewTableOfContentServlet` | `ViewTableOfContentServlet` | `/ViewTableOfContent` | - |

### `at.ac.meduniwien.ophthalmology.libreclinica.control.rule` (1 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `ExecuteCrossEditCheckServlet` | `ExecuteCrossEditCheckServlet` | `/ExecuteCrossEditCheck` | - |

### `at.ac.meduniwien.ophthalmology.libreclinica.control.submit` (38 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `AddNewSubjectServlet` | `AddNewSubjectServlet` | `/AddNewSubject` | - |
| `AdministrativeEditingServlet` | `AdministrativeEditingServlet` | `/AdministrativeEditing` | - |
| `CheckCRFLocked` | `CheckCRFLocked` | `/CheckCRFLocked` | - |
| `CreateDiscrepancyNoteServlet` | `CreateDiscrepancyNoteServlet` | `/CreateDiscrepancyNote` | - |
| `CreateNewStudyEventServlet` | `CreateNewStudyEventServlet` | `/CreateNewStudyEvent` | - |
| `CreateOneDiscrepancyNoteServlet` | `CreateOneDiscrepancyNoteServlet` | `/CreateOneDiscrepancyNote` | - |
| `DataEntryServlet` | `DataEntryServlet` | `/DataEntry` | - |
| `DoubleDataEntryServlet` | `DoubleDataEntryServlet` | `/DoubleDataEntry` | - |
| `DownloadAttachedFileServlet` | `DownloadAttachedFileServlet` | `/DownloadAttachedFile` | - |
| `DownloadRuleSetXmlServlet` | `DownloadRuleSetXmlServlet` | `/DownloadRuleSetXml` | - |
| `EnterDataForStudyEventServlet` | `EnterDataForStudyEventServlet` | `/EnterDataForStudyEvent` | - |
| `FindStudyEventServlet` | `FindStudyEventServlet` | `/FindStudyEvent` | - |
| `FindSubjectsDataServlet` | `FindSubjectsDataServlet` | `/FindSubjectsData` | - |
| `ImportCRFDataServlet` | `ImportCRFDataServlet` | `/ImportCRFData` | - |
| `ImportRuleServlet` | `ImportRuleServlet` | `/ImportRule` | - |
| `InitialDataEntryServlet` | `InitialDataEntryServlet` | `/InitialDataEntry` | - |
| `ListStudySubjectsServlet` | `ListStudySubjectsServlet` | `/ListStudySubjects` | - |
| `ListStudySubjectsSubmitServlet` | `ListStudySubjectsSubmitServlet` | `/ListStudySubjectsSubmit` | - |
| `MarkEventCRFCompleteServlet` | `MarkEventCRFCompleteServlet` | `/MarkEventCRFComplete` | - |
| `MatchPasswordServlet` | `MatchPasswordServlet` | `/MatchPassword` | - |
| `ParticipantFormServlet` | `ParticipantFormServlet` | `/ParticipantFormServlet` | - |
| `RemoveRuleSetServlet` | `RemoveRuleSetServlet` | `/RemoveRuleSet` | - |
| `RestoreRuleSetServlet` | `RestoreRuleSetServlet` | `/RestoreRuleSet` | - |
| `RunRuleServlet` | `RunRuleServlet` | `/RunRule` | - |
| `RunRuleSetServlet` | `RunRuleSetServlet` | `/RunRuleSet` | - |
| `SubmitDataServlet` | `SubmitDataServlet` | `/SubmitData` | - |
| `TableOfContentsServlet` | `TableOfContentsServlet` | `/TableOfContents` | - |
| `TestRuleServlet` | `TestRuleServlet` | `/TestRule` | - |
| `UpdateRuleSetRuleServlet` | `UpdateRuleSetRuleServlet` | `/UpdateRuleSetRule` | - |
| `UploadFileServlet` | `UploadFileServlet` | `/UploadFile` | - |
| `VerifyImportedCRFDataServlet` | `VerifyImportedCRFDataServlet` | `/VerifyImportedCRFData` | - |
| `VerifyImportedRuleServlet` | `VerifyImportedRuleServlet` | `/VerifyImportedRule` | - |
| `ViewDiscrepancyNoteServlet` | `ViewDiscrepancyNoteServlet` | `/ViewDiscrepancyNote` | - |
| `ViewRuleAssignmentDataServlet` | `ViewRuleAssignmentDataServlet` | `/ViewRuleAssignmentData` | - |
| `ViewRuleAssignmentNewServlet` | `ViewRuleAssignmentNewServlet` | `/ViewRuleAssignment` | - |
| `ViewRuleAssignmentServlet` | `ViewRuleAssignmentServlet` | `/ViewRuleAssignmentNew` | - |
| `ViewRuleSetAuditServlet` | `ViewRuleSetAuditServlet` | `/ViewRuleSetAudit` | - |
| `ViewRuleSetServlet` | `ViewRuleSetServlet` | `/ViewRuleSet` | - |

### `at.ac.meduniwien.ophthalmology.libreclinica.control.techadmin` (2 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `TechAdminServlet` | `TechAdminServlet` | `/TechAdmin` | - |
| `ViewSchedulerServlet` | `ViewSchedulerServlet` | `/ViewScheduler` | - |

### `at.ac.meduniwien.ophthalmology.libreclinica.control.urlRewrite` (1 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `urlRewriterServlet` | `UrlRewriteServlet` | `/ClinicalData/html/view/*` | - |

### `at.ac.meduniwien.ophthalmology.libreclinica.view.form` (1 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `FormServlet` | `FormServlet` | `/form` | - |

### `at.ac.meduniwien.ophthalmology.libreclinica.web` (1 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `SQLInitServlet` | `SQLInitServlet` | *(unmapped — dead code candidate)* | 1 |

### `at.ac.meduniwien.ophthalmology.libreclinica.web.openrosa` (1 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `OpenRosaFormDownloadServlet` | `OpenRosaFormDownloadServlet` | `/openrosa/formXml` | - |

### `com.sun.jersey.spi.spring.container.servlet` (2 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `OpenClinicaJersey` | `SpringServlet` | `/rest/*` | 1 |
| `OpenClinicaJersey2` | `SpringServlet` | `/rest2/*` | 1 |

### `org.springframework.web.servlet` (1 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `pages` | `DispatcherServlet` | `/pages/*`, `/oauth/*` | 2 |

### `org.springframework.ws.transport.http` (1 servlets)

| Servlet name | Class | URL pattern | load-on-startup |
|---|---|---|---|
| `ws` | `MessageDispatcherServlet` | `/ws/*` | - |

---

## Migration plan for the 218 legacy LibreClinica servlets

All 218 legacy servlets extend `SecureController` (or a subclass thereof) and follow the same lifecycle pattern. The most efficient migration is **bulk `ServletRegistrationBean` registration** via a single `@Configuration` class generated mechanically from this inventory:

```java
@Configuration
public class LegacyServletRegistry {
    @Bean
    public ServletRegistrationBean<MainMenuServlet> mainMenuServlet() {
        return new ServletRegistrationBean<>(new MainMenuServlet(), "/MainMenu");
    }
    // ... 217 more entries
}
```

Each bean method is ~3-4 lines. The whole class is mechanical — generate it from this inventory by `awk`/`python` over the markdown.

### Alternative: `@WebServlet` annotations + `@ServletComponentScan`

Annotate each of the 218 servlet classes with `@WebServlet("/UrlPattern")`, add `@ServletComponentScan` to `LibreClinicaApplication`. Spring Boot picks them up. Lower per-class LOC but requires touching every servlet file — higher git churn for the cliff PR.

**Recommendation:** `LegacyServletRegistry` @Bean class. One file to author + diff-review.

---

## Zombie candidates (verify and delete before the cliff)

Mapping-less servlets and Phase B retirees:

- **`org.springframework.ws.transport.http.MessageDispatcherServlet`** — Phase B PR #31 dropped the `ws/` Maven module but the `web.xml` `ws` servlet + `ws-servlet-config.xml` still reference `spring-ws`. Verify the SOAP WSDL endpoints are unused; if so, delete the servlet entries + the WS XML config + the remaining `spring-ws` dep pins.
- **`OpenClinicaJersey` + `OpenClinicaJersey2`** (Jersey JAX-RS) — verify these REST endpoints are still in use; if not, delete + drop `com.sun.jersey` deps from poms.
- Any servlet whose URL pattern is *(unmapped — dead code candidate)* in the per-package tables above.