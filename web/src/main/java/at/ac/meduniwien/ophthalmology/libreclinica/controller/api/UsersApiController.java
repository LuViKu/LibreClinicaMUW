/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;
import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.user.AuthoritiesBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuthoritiesDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase E.4 M12 — Data-Manager users adapter.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET /pages/api/v1/users?role=…&siteOid=…&active=…}
 *       — lists all users bound to the session-bound active study,
 *       one row per (user × study-role) assignment. Server-side
 *       filters narrow before the wire; the SPA additionally runs
 *       the same filters client-side for snappy dropdown UX.</li>
 * </ul>
 *
 * <p>Auth source derivation:
 * <ul>
 *   <li>{@code external_id_provider} non-blank → {@code sso}</li>
 *   <li>{@code authtype} contains "ldap" (case-insensitive) → {@code ldap}</li>
 *   <li>{@code last_visit_date} is null → {@code pending-invite}</li>
 *   <li>else → {@code local}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Study-user roster (Manage Users).")
public class UsersApiController {

    private static final Logger LOG = LoggerFactory.getLogger(UsersApiController.class);

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;
    private final SecurityManager securityManager;
    private final AuthoritiesDao authoritiesDao;

    @Autowired
    public UsersApiController(@Qualifier("dataSource") DataSource dataSource,
                              SiteVisibilityFilter siteVisibilityFilter,
                              @Qualifier("securityManager") SecurityManager securityManager,
                              AuthoritiesDao authoritiesDao) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.securityManager = securityManager;
        this.authoritiesDao = authoritiesDao;
    }

    @GetMapping
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = StudyUserDto.class)))
    public ResponseEntity<?> list(
            @RequestParam(value = "role", required = false) String roleFilter,
            @RequestParam(value = "siteOid", required = false) String siteOidFilter,
            @RequestParam(value = "active", required = false) Boolean activeFilter,
            HttpSession session) {

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }

        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);

        // A4 — per-site visibility. The legacy
        // findAllUsersByStudy(parent) already returns roles for the
        // parent + every site under it; for narrower views (Monitor
        // with one site grant) we filter the result by the user's
        // visible study set.
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
        Set<Integer> visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                me, currentStudy, currentRole);

        // Roles scoped to the active study + its sites. The DAO
        // returns one StudyUserRoleBean per (user, study) pair.
        ArrayList<StudyUserRoleBean> bindings = userDao.findAllUsersByStudy(currentStudy.getId());

        Map<Integer, UserAccountBean> userCache = new HashMap<>();
        Map<Integer, StudyBean> studyCache = new HashMap<>();
        List<StudyUserDto> out = new ArrayList<>();

        for (StudyUserRoleBean sur : bindings) {
            // A4 — skip bindings outside the visible study tree.
            if (!visibleStudyIds.contains(sur.getStudyId())) continue;

            UserAccountBean ua = userCache.computeIfAbsent(sur.getUserAccountId(),
                    id -> userDao.findByPK(id));
            if (ua == null || ua.getId() == 0) continue;

            StudyBean roleStudy = sur.getStudyId() > 0
                    ? studyCache.computeIfAbsent(sur.getStudyId(),
                        id -> (StudyBean) studyDao.findByPK(id))
                    : null;
            boolean isSite = roleStudy != null && roleStudy.getParentStudyId() > 0;

            String spaRole = sur.getRole() != null
                    ? RoleMapper.toSpaRole(sur.getRole().getName()) : "Investigator";
            String siteLabel = isSite && roleStudy != null ? roleStudy.getName() : null;
            String auth = authForUser(ua);
            String lastLogin = ua.getLastVisitDate() == null ? null
                    : ua.getLastVisitDate().toInstant().atZone(ZoneOffset.UTC)
                            .truncatedTo(ChronoUnit.SECONDS).toInstant().toString();
            boolean active = ua.getStatus() != null && ua.getStatus().getId() == Status.AVAILABLE.getId();

            if (roleFilter != null && !roleFilter.isBlank()
                    && !roleFilter.equalsIgnoreCase(spaRole)) continue;
            if (siteOidFilter != null && !siteOidFilter.isBlank()) {
                if (roleStudy == null || !siteOidFilter.equalsIgnoreCase(roleStudy.getOid())) continue;
            }
            if (activeFilter != null && active != activeFilter) continue;

            out.add(new StudyUserDto(
                    String.valueOf(ua.getId()),
                    nullToEmpty(ua.getName()),
                    displayName(ua),
                    blankToNull(ua.getEmail()),
                    spaRole,
                    siteLabel,
                    auth,
                    lastLogin,
                    active));
        }

        return ResponseEntity.ok(out);
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/users  (Phase E A7.1 — create new user)              */
    /* ----------------------------------------------------------------- */

    /**
     * Provision a new user account + initial study/role binding.
     *
     * <p>Mirrors {@code CreateUserAccountServlet} (legacy admin
     * surface): sysadmin-only, generates an 8-char one-time password,
     * BCrypt-hashes it, sets {@code passwd_timestamp = null} (force
     * change on first login), bundles a {@link StudyUserRoleBean} for
     * the initial study binding, writes the {@link AuthoritiesBean}
     * row for Spring Security.
     *
     * <p>The cleartext one-time password is returned in the response
     * body when {@code sendEmail == false}; when {@code sendEmail ==
     * true} the response carries {@code generatedPassword = null} and
     * the admin must distribute manually until the {@code MailService}
     * extraction lands (deferred to a follow-up slice).
     *
     * <p>The legacy 2FA secret-init branch
     * ({@code CreateUserAccountServlet:252–258}) is intentionally
     * skipped: the create-side bean's {@code two_factor_marked} flag
     * is never set during create, so the legacy code is defensive
     * dead code on this path. Self-service 2FA enrollment is owned by
     * a separate B-series slice.
     *
     * <p>Status codes: {@code 201} on success, {@code 400} on
     * validation failure, {@code 401} when unauthenticated,
     * {@code 403} when the caller is not a sysadmin.
     */
    @PostMapping
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = StudyUserDto.class)))
    public ResponseEntity<?> create(@RequestBody(required = false) CreateUserRequest body,
                                    HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        if (!UserAdminAuthorization.roleMayAdministerUsers(me)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit user administration — sysadmin only"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        // Shape-level validation (no DAO calls) — pinned by MockMvc tests.
        List<SubjectsApiController.ValidationErrorBody.FieldError> errors = validateCreateUserShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        // DAO-bound validation — uniqueness + study existence + role legality.
        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);
        List<SubjectsApiController.ValidationErrorBody.FieldError> daoErrors =
                validateCreateUserAgainstDb(body, userDao, studyDao);
        if (!daoErrors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", daoErrors));
        }

        Role legacyRole = RoleMapper.fromSpaRole(body.role());
        StudyBean initialStudy = (StudyBean) studyDao.findByPK(body.studyId());

        // Resolve user-type. Default USER. SYSADMIN allowed. TECHADMIN
        // requires the caller themselves be tech-admin (legacy parity).
        UserType userType = UserType.USER;
        if (body.userType() != null) {
            userType = switch (body.userType()) {
                case "USER" -> UserType.USER;
                case "SYSADMIN" -> UserType.SYSADMIN;
                case "TECHADMIN" -> UserType.TECHADMIN;
                default -> UserType.USER;
            };
            if (userType == UserType.TECHADMIN && !me.isTechAdmin()) {
                return ResponseEntity.status(403).body(Map.of("message",
                        "Only a TechAdmin may create another TechAdmin user"));
            }
        }

        boolean runWs = Boolean.TRUE.equals(body.runWebservices());
        String generatedPassword = securityManager.genPassword();
        String passwordHash = securityManager.encryptPassword(generatedPassword, runWs);

        UserAccountBean newUser = new UserAccountBean();
        newUser.setName(body.username().trim());
        newUser.setFirstName(body.firstName().trim());
        newUser.setLastName(body.lastName().trim());
        newUser.setEmail(body.email().trim());
        newUser.setInstitutionalAffiliation(body.institutionalAffiliation().trim());
        newUser.setPhone(body.phone() == null ? "" : body.phone().trim());
        newUser.setPasswd(passwordHash);
        newUser.setPasswdTimestamp(null);
        newUser.setStatus(Status.AVAILABLE);
        newUser.setOwner(me);
        newUser.setRunWebservices(runWs);
        newUser.setAuthtype(body.authtype() == null ? "" : body.authtype().trim());
        newUser.setAccessCode("null");
        newUser.setEnableApiKey(true);
        newUser.setApiKey(generateUniqueApiKey(userDao));
        newUser.addUserType(userType);

        StudyUserRoleBean initialBinding = new StudyUserRoleBean();
        initialBinding.setStudyId(body.studyId());
        initialBinding.setRoleName(legacyRole.getName());
        initialBinding.setStatus(Status.AVAILABLE);
        initialBinding.setOwner(me);
        newUser.addRole(initialBinding);
        newUser.setActiveStudyId(body.studyId());

        UserAccountBean persisted = userDao.create(newUser);
        if (persisted == null || persisted.getId() == 0) {
            LOG.warn("UserAccountDAO.create returned no row for username={}", body.username());
            return ResponseEntity.status(500).body(Map.of("message",
                    "Failed to persist new user"));
        }

        try {
            authoritiesDao.saveOrUpdate(new AuthoritiesBean(persisted.getName()));
        } catch (RuntimeException re) {
            LOG.warn("authoritiesDao.saveOrUpdate failed for username={} — Spring Security row missing",
                    persisted.getName(), re);
        }

        LOG.info("Create user: username={} by admin={} initialStudy={} initialRole={}",
                persisted.getName(), me.getName(), body.studyId(), legacyRole.getName());

        String spaRole = RoleMapper.toSpaRole(legacyRole.getName());
        boolean isSite = initialStudy.getParentStudyId() > 0;
        String siteLabel = isSite ? initialStudy.getName() : null;
        StudyUserDto dto = new StudyUserDto(
                String.valueOf(persisted.getId()),
                nullToEmpty(persisted.getName()),
                displayName(persisted),
                blankToNull(persisted.getEmail()),
                spaRole,
                siteLabel,
                authForUser(persisted),
                null,
                true);

        Map<String, Object> response = new HashMap<>();
        response.put("user", dto);
        response.put("generatedPassword",
                Boolean.FALSE.equals(body.sendEmail()) ? generatedPassword : null);
        return ResponseEntity.status(201).body(response);
    }

    private static List<SubjectsApiController.ValidationErrorBody.FieldError> validateCreateUserShape(
            CreateUserRequest body) {

        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();

        String username = body.username() == null ? "" : body.username().trim();
        if (username.isEmpty()) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    "username", "Username is required"));
        } else if (username.length() > 64) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    "username", "Username must be 64 characters or fewer"));
        } else if (!username.matches("[A-Za-z0-9_]+")) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    "username", "Username may contain only letters, digits, and underscores"));
        }

        requireNonBlank(body.firstName(), "firstName", 50, "First name", out);
        requireNonBlank(body.lastName(), "lastName", 50, "Last name", out);

        String email = body.email() == null ? "" : body.email().trim();
        if (email.isEmpty()) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    "email", "Email is required"));
        } else if (email.length() > 120) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    "email", "Email must be 120 characters or fewer"));
        } else if (!email.matches(".+@.+\\..*")) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    "email", "Email format is invalid"));
        }

        requireNonBlank(body.institutionalAffiliation(), "institutionalAffiliation", 255,
                "Institutional affiliation", out);

        if (body.studyId() == null || body.studyId() <= 0) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    "studyId", "Initial studyId is required"));
        }

        if (RoleMapper.fromSpaRole(body.role()) == null) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    "role", "Unknown role — expected Administrator / Data Manager / CRC / Monitor / Investigator"));
        }

        if ("ldap".equalsIgnoreCase(body.userSource())) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    "userSource", "LDAP user provisioning is not supported via this endpoint"));
        }

        return out;
    }

    private static List<SubjectsApiController.ValidationErrorBody.FieldError> validateCreateUserAgainstDb(
            CreateUserRequest body, UserAccountDAO userDao, StudyDAO studyDao) {

        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();

        UserAccountBean existing = (UserAccountBean) userDao.findByUserName(body.username().trim());
        if (existing != null && existing.getId() != 0) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    "username", "Username already exists"));
        }

        StudyBean study = (StudyBean) studyDao.findByPK(body.studyId());
        if (study == null || study.getId() == 0) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    "studyId", "No study with that id"));
        } else {
            Role legacyRole = RoleMapper.fromSpaRole(body.role());
            if (legacyRole != null
                    && !UserAdminAuthorization.roleAssignmentIsLegal(legacyRole, study)) {
                out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                        "role", "Role '" + body.role()
                                + "' cannot be granted at site level — assign at the parent study"));
            }
        }

        return out;
    }

    private static void requireNonBlank(String v, String field, int max, String label,
            List<SubjectsApiController.ValidationErrorBody.FieldError> out) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    field, label + " is required"));
        } else if (s.length() > max) {
            out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                    field, label + " must be " + max + " characters or fewer"));
        }
    }

    private static String generateUniqueApiKey(UserAccountDAO userDao) {
        // Mirror CreateUserAccountServlet:260–264 — retry until unique.
        for (int attempt = 0; attempt < 16; attempt++) {
            String candidate = UUID.randomUUID().toString().replace("-", "");
            UserAccountBean existing = (UserAccountBean) userDao.findByApiKey(candidate);
            if (existing == null || existing.getId() == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate a unique API key after 16 attempts");
    }

    /* ----------------------------------------------------------------- */
    /* PUT /api/v1/users/{username}  (Phase E A7.2 — edit profile)       */
    /* ----------------------------------------------------------------- */

    /**
     * Edit profile fields on an existing user account.
     *
     * <p>Mirrors {@code EditUserAccountServlet:114–191}: sysadmin-only,
     * editable fields are firstName / lastName / email / phone /
     * institutionalAffiliation / userType / authtype / runWebservices.
     * Username, password, and lifecycle (disable/restore) are covered
     * by separate endpoints (A7.4 / A7.3).
     *
     * <p>Per-field diff + audit emission: one
     * {@code audit_log_event} row per changed column, mirroring the
     * Phase E A2 {@code SubjectsApiController.writeSubjectFieldAudit}
     * pattern. Legacy {@code EditUserAccountServlet} doesn't emit
     * per-field audit rows — this is additive for GCP fidelity
     * (DR-009 audit-on-write).
     *
     * <p>{@code userType=TECHADMIN} requests are forbidden unless the
     * caller themselves is a TechAdmin (parity with create-side gate
     * in {@code CreateUserAccountServlet:113}).
     *
     * <p>If {@code runWebservices} is flipped to true, a fresh API key
     * is generated (mirrors {@code EditUserAccountServlet:178–182}).
     *
     * <p>Status codes: {@code 200} on success (returns updated
     * {@link StudyUserDto}), {@code 400} on validation, {@code 401}
     * unauthenticated, {@code 403} non-sysadmin, {@code 404} unknown
     * username.
     */
    @PutMapping("/{username}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyUserDto.class)))
    public ResponseEntity<?> update(@PathVariable("username") String username,
                                    @RequestBody(required = false) UpdateUserRequest body,
                                    HttpSession session) {
        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        if (me == null || me.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        if (currentStudy == null || currentStudy.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
        }
        if (!UserAdminAuthorization.roleMayAdministerUsers(me)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "Your role does not permit user administration — sysadmin only"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        List<SubjectsApiController.ValidationErrorBody.FieldError> errors = validateUpdateUserShape(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }

        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        UserAccountBean target = (UserAccountBean) userDao.findByUserName(username);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No user with username '" + username + "'"));
        }

        UserType requestedType = null;
        if (body.userType() != null) {
            requestedType = switch (body.userType()) {
                case "USER" -> UserType.USER;
                case "SYSADMIN" -> UserType.SYSADMIN;
                case "TECHADMIN" -> UserType.TECHADMIN;
                default -> null;
            };
            if (requestedType == UserType.TECHADMIN && !me.isTechAdmin()) {
                return ResponseEntity.status(403).body(Map.of("message",
                        "Only a TechAdmin may grant the TECHADMIN user type"));
            }
        }

        AuditEventDAO auditDAO = new AuditEventDAO(dataSource);

        if (body.firstName() != null) {
            String oldVal = target.getFirstName();
            String newVal = body.firstName().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setFirstName(newVal);
                writeUserFieldAudit(auditDAO, me, target, "first_name", oldVal, newVal);
            }
        }
        if (body.lastName() != null) {
            String oldVal = target.getLastName();
            String newVal = body.lastName().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setLastName(newVal);
                writeUserFieldAudit(auditDAO, me, target, "last_name", oldVal, newVal);
            }
        }
        if (body.email() != null) {
            String oldVal = target.getEmail();
            String newVal = body.email().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setEmail(newVal);
                writeUserFieldAudit(auditDAO, me, target, "email", oldVal, newVal);
            }
        }
        if (body.phone() != null) {
            String oldVal = target.getPhone();
            String newVal = body.phone().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setPhone(newVal);
                writeUserFieldAudit(auditDAO, me, target, "phone", oldVal, newVal);
            }
        }
        if (body.institutionalAffiliation() != null) {
            String oldVal = target.getInstitutionalAffiliation();
            String newVal = body.institutionalAffiliation().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setInstitutionalAffiliation(newVal);
                writeUserFieldAudit(auditDAO, me, target, "institutional_affiliation", oldVal, newVal);
            }
        }
        if (body.authtype() != null) {
            String oldVal = target.getAuthtype();
            String newVal = body.authtype().trim();
            if (!java.util.Objects.equals(nullToEmpty(oldVal), newVal)) {
                target.setAuthtype(newVal);
                writeUserFieldAudit(auditDAO, me, target, "authtype", oldVal, newVal);
            }
        }
        if (requestedType != null) {
            UserType oldType = currentUserType(target);
            if (oldType != requestedType) {
                // addUserType internally clears + sets — legacy parity
                // (EditUserAccountServlet replaces the user_type via
                // the same path).
                target.addUserType(requestedType);
                writeUserFieldAudit(auditDAO, me, target, "user_type",
                        oldType.getName(), requestedType.getName());
            }
        }
        if (body.runWebservices() != null) {
            boolean oldVal = target.getRunWebservices();
            boolean newVal = body.runWebservices();
            if (oldVal != newVal) {
                target.setRunWebservices(newVal);
                writeUserFieldAudit(auditDAO, me, target, "run_webservices",
                        Boolean.toString(oldVal), Boolean.toString(newVal));
                if (newVal) {
                    // Flipping run_webservices on regenerates the API key
                    // (mirrors EditUserAccountServlet:178–182).
                    target.setApiKey(generateUniqueApiKey(userDao));
                }
            }
        }

        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        userDao.update(target);

        LOG.info("Update user: username={} by admin={}",
                target.getName(), me.getName());

        return ResponseEntity.ok(projectToStudyUserDto(target, currentStudy));
    }

    private static List<SubjectsApiController.ValidationErrorBody.FieldError> validateUpdateUserShape(
            UpdateUserRequest body) {
        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();

        // Each field is optional (null = unchanged); if present, run the
        // same length/format rules as create.
        if (body.firstName() != null) {
            String s = body.firstName().trim();
            if (s.isEmpty()) out.add(fieldError("firstName", "First name cannot be blank"));
            else if (s.length() > 50) out.add(fieldError("firstName", "First name must be 50 characters or fewer"));
        }
        if (body.lastName() != null) {
            String s = body.lastName().trim();
            if (s.isEmpty()) out.add(fieldError("lastName", "Last name cannot be blank"));
            else if (s.length() > 50) out.add(fieldError("lastName", "Last name must be 50 characters or fewer"));
        }
        if (body.email() != null) {
            String s = body.email().trim();
            if (s.isEmpty()) out.add(fieldError("email", "Email cannot be blank"));
            else if (s.length() > 120) out.add(fieldError("email", "Email must be 120 characters or fewer"));
            else if (!s.matches(".+@.+\\..*")) out.add(fieldError("email", "Email format is invalid"));
        }
        if (body.institutionalAffiliation() != null) {
            String s = body.institutionalAffiliation().trim();
            if (s.isEmpty()) out.add(fieldError("institutionalAffiliation", "Institutional affiliation cannot be blank"));
            else if (s.length() > 255) out.add(fieldError("institutionalAffiliation", "Institutional affiliation must be 255 characters or fewer"));
        }
        if (body.userType() != null) {
            String s = body.userType();
            if (!"USER".equals(s) && !"SYSADMIN".equals(s) && !"TECHADMIN".equals(s)) {
                out.add(fieldError("userType", "userType must be one of USER / SYSADMIN / TECHADMIN"));
            }
        }
        return out;
    }

    private static SubjectsApiController.ValidationErrorBody.FieldError fieldError(String field, String msg) {
        return new SubjectsApiController.ValidationErrorBody.FieldError(field, msg);
    }

    /**
     * Derive the single legacy {@link UserType} a bean carries. The
     * legacy bean stores types as a list (forward-compat) but in
     * practice always carries exactly one — {@link UserAccountBean#addUserType}
     * clears before adding. Use {@code isSysAdmin / isTechAdmin} flags
     * to recover it instead of the (private) backing collection.
     */
    private static UserType currentUserType(UserAccountBean ua) {
        if (ua.isTechAdmin()) return UserType.TECHADMIN;
        if (ua.isSysAdmin()) return UserType.SYSADMIN;
        return UserType.USER;
    }

    private void writeUserFieldAudit(AuditEventDAO auditDAO,
                                     UserAccountBean editor,
                                     UserAccountBean target,
                                     String columnName,
                                     String oldValue,
                                     String newValue) {
        try {
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(editor.getId());
            ae.setAuditTable("user_account");
            ae.setEntityId(target.getId());
            ae.setColumnName(columnName);
            ae.setOldValue(oldValue == null ? "" : oldValue);
            ae.setNewValue(newValue == null ? "" : newValue);
            ae.setActionMessage("user_profile_update: " + (target.getName() == null ? "" : target.getName())
                    + "." + columnName
                    + " '" + (oldValue == null ? "" : oldValue) + "' → '"
                    + (newValue == null ? "" : newValue) + "'");
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for user field {}={} (continuing): {}",
                    columnName, newValue, e.getMessage());
        }
    }

    /**
     * Project a persisted {@link UserAccountBean} (post-edit) back to
     * the SPA wire shape. Uses the same role/site resolution rules as
     * the list endpoint — the user's active role binding under the
     * current study is the canonical row.
     */
    private StudyUserDto projectToStudyUserDto(UserAccountBean ua, StudyBean currentStudy) {
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyUserRoleBean activeRole = ua.getRoleByStudy(currentStudy.getId());
        String spaRole = activeRole != null && activeRole.getRole() != null
                ? RoleMapper.toSpaRole(activeRole.getRole().getName()) : "Investigator";
        StudyBean roleStudy = activeRole != null && activeRole.getStudyId() > 0
                ? (StudyBean) studyDao.findByPK(activeRole.getStudyId()) : null;
        String siteLabel = roleStudy != null && roleStudy.getParentStudyId() > 0
                ? roleStudy.getName() : null;
        String lastLogin = ua.getLastVisitDate() == null ? null
                : ua.getLastVisitDate().toInstant().atZone(ZoneOffset.UTC)
                        .truncatedTo(ChronoUnit.SECONDS).toInstant().toString();
        boolean active = ua.getStatus() != null && ua.getStatus().getId() == Status.AVAILABLE.getId();
        return new StudyUserDto(
                String.valueOf(ua.getId()),
                nullToEmpty(ua.getName()),
                displayName(ua),
                blankToNull(ua.getEmail()),
                spaRole,
                siteLabel,
                authForUser(ua),
                lastLogin,
                active);
    }

    /* ----------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ----------------------------------------------------------------- */

    private static String authForUser(UserAccountBean ua) {
        String provider = ua.getExternalIdProvider();
        if (provider != null && !provider.isBlank()) return "sso";
        String authtype = ua.getAuthtype();
        if (authtype != null && authtype.toLowerCase().contains("ldap")) return "ldap";
        if (ua.getLastVisitDate() == null) return "pending-invite";
        return "local";
    }

    private static String displayName(UserAccountBean ua) {
        String first = ua.getFirstName() == null ? "" : ua.getFirstName().trim();
        String last = ua.getLastName() == null ? "" : ua.getLastName().trim();
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? nullToEmpty(ua.getName()) : joined;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
