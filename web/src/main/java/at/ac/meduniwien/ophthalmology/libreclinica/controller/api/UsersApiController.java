/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import at.ac.meduniwien.ophthalmology.libreclinica.config.SsoProperties;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final SsoProperties ssoProperties;

    @Autowired
    public UsersApiController(@Qualifier("dataSource") DataSource dataSource,
                              SiteVisibilityFilter siteVisibilityFilter,
                              @Qualifier("securityManager") SecurityManager securityManager,
                              AuthoritiesDao authoritiesDao,
                              SsoProperties ssoProperties) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
        this.securityManager = securityManager;
        this.authoritiesDao = authoritiesDao;
        this.ssoProperties = ssoProperties;
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

        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);

        // Phase E.6 (2026-06-03 evening): sysadmin / techadmin callers
        // get a PLATFORM-WIDE user list. The user-list view is the
        // entry point for user account lifecycle (invite, disable,
        // reset password); role bindings on individual studies are
        // managed from the per-user Roles dialog which can grant any
        // study. Scoping the list itself to the active study made the
        // admin landing's "Manage Users" surface inconsistent — a
        // newly-invited user wouldn't appear until the admin switched
        // to a study where the new binding existed.
        //
        // For non-sysadmin callers (DM coordinator etc.) we keep the
        // study-scoped list — they should only see users who have a
        // binding on their currently active study.
        ArrayList<StudyUserRoleBean> bindings;
        Set<Integer> visibleStudyIds;
        boolean globalList = me.isSysAdmin() || me.isTechAdmin();
        if (globalList) {
            // Platform-wide list: walk every user, then collect their
            // active bindings via findAllRolesByUserName. N+1 query but
            // N is tiny at MUW scale (low hundreds of users); avoids
            // adding a new SQL+DAO method.
            bindings = new ArrayList<>();
            ArrayList<UserAccountBean> everyone = userDao.findAll();
            for (UserAccountBean ua : everyone) {
                if (ua == null || ua.getId() == 0) continue;
                ArrayList<StudyUserRoleBean> userRoles = userDao.findAllRolesByUserName(ua.getName());
                // Synthesize a (user, no-study) row when the user has
                // no active binding anywhere — otherwise sysadmin-only
                // newly-invited accounts wouldn't appear at all.
                boolean addedAny = false;
                for (StudyUserRoleBean r : userRoles) {
                    if (r.getStatus() != null
                            && r.getStatus().getId() == at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status.AVAILABLE.getId()) {
                        r.setUserAccountId(ua.getId());
                        bindings.add(r);
                        addedAny = true;
                    }
                }
                if (!addedAny) {
                    StudyUserRoleBean synth = new StudyUserRoleBean();
                    synth.setStudyId(0);
                    synth.setUserAccountId(ua.getId());
                    synth.setStatus(at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status.AVAILABLE);
                    bindings.add(synth);
                }
            }
            visibleStudyIds = null;
        } else {
            if (currentStudy == null || currentStudy.getId() == 0) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "No active study bound — call POST /pages/api/v1/me/activeStudy first"));
            }
            // A4 — per-site visibility. The legacy
            // findAllUsersByStudy(parent) already returns roles for the
            // parent + every site under it; for narrower views (Monitor
            // with one site grant) we filter the result by the user's
            // visible study set.
            StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");
            visibleStudyIds = siteVisibilityFilter.visibleStudyIds(
                    me, currentStudy, currentRole);
            bindings = userDao.findAllUsersByStudy(currentStudy.getId());
        }

        Map<Integer, UserAccountBean> userCache = new HashMap<>();
        Map<Integer, StudyBean> studyCache = new HashMap<>();
        // user_id → best-row-so-far. The best row is the one whose
        // projected SPA role ranks highest in ROLE_PRIORITY.
        Map<Integer, StudyUserDto> bestByUser = new LinkedHashMap<>();

        for (StudyUserRoleBean sur : bindings) {
            // A4 — skip bindings outside the visible study tree. For
            // sysadmin's global list visibleStudyIds is null, meaning
            // "no restriction".
            if (visibleStudyIds != null && !visibleStudyIds.contains(sur.getStudyId())) continue;
            // For the global list, filter inactive bindings here too
            // (the legacy study-scoped DAO query already includes
            // status_id=1; findAllRoles returns every row).
            if (globalList) {
                if (sur.getStatus() == null
                        || sur.getStatus().getId() != at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status.AVAILABLE.getId()) {
                    continue;
                }
            }

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
            // Phase E.6: sysadmin/techadmin always project to Administrator
            // — matches MeApiController so the user-list role chip and
            // the role-chip in the top bar agree for these users.
            if (ua.isSysAdmin() || ua.isTechAdmin()) spaRole = "Administrator";
            String siteLabel = isSite && roleStudy != null ? roleStudy.getName() : null;
            String auth = authForUser(ua);
            String lastLogin = ua.getLastVisitDate() == null ? null
                    : java.time.Instant.ofEpochMilli(ua.getLastVisitDate().getTime())
                            .atZone(ZoneOffset.UTC)
                            .truncatedTo(ChronoUnit.SECONDS).toInstant().toString();
            boolean active = ua.getStatus() != null && ua.getStatus().getId() == Status.AVAILABLE.getId();

            if (roleFilter != null && !roleFilter.isBlank()
                    && !roleFilter.equalsIgnoreCase(spaRole)) continue;
            if (siteOidFilter != null && !siteOidFilter.isBlank()) {
                if (roleStudy == null || !siteOidFilter.equalsIgnoreCase(roleStudy.getOid())) continue;
            }
            if (activeFilter != null && active != activeFilter) continue;

            boolean locked = Boolean.FALSE.equals(ua.getAccountNonLocked());
            StudyUserDto candidate = new StudyUserDto(
                    String.valueOf(ua.getId()),
                    nullToEmpty(ua.getName()),
                    displayName(ua),
                    blankToNull(ua.getEmail()),
                    spaRole,
                    siteLabel,
                    auth,
                    lastLogin,
                    active,
                    locked);
            StudyUserDto current = bestByUser.get(ua.getId());
            if (current == null || rolePriority(spaRole) > rolePriority(current.role())) {
                bestByUser.put(ua.getId(), candidate);
            }
        }

        return ResponseEntity.ok(new ArrayList<>(bestByUser.values()));
    }

    /**
     * Phase E.6 — projected-role priority for user-list deduplication.
     * Administrator wins over Data Manager wins over Monitor / CRC /
     * Investigator. Matches the conventional "highest grant wins"
     * model that the legacy authorization helpers ({@code UserAdminAuthorization.
     * roleMayAdministerUsers}) implement.
     */
    private static int rolePriority(String spaRole) {
        return switch (spaRole) {
            case "Administrator" -> 5;
            case "Data Manager"  -> 4;
            case "Monitor"       -> 3;
            case "CRC"           -> 2;
            case "Investigator"  -> 1;
            default              -> 0;
        };
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

        // Phase E.6 (DR-014 follow-up): pre-empt the composite-unique
        // collision on (external_id_provider, external_id). Bubbling
        // the SQLState up as a 500 would be opaque; explicit 409 with
        // the conflicting field makes the SPA's per-field error
        // rendering work.
        if (body.externalId() != null && !body.externalId().trim().isEmpty()) {
            String provider = ssoProperties.getProvider().getName();
            String eppn = body.externalId().trim();
            UserAccountBean clash = userDao.findByExternalIdentity(provider, eppn);
            if (clash != null && clash.getId() != 0) {
                return ResponseEntity.status(409).body(new SubjectsApiController.ValidationErrorBody(
                        "An account is already bound to that institutional principal",
                        List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                                "externalId",
                                "User '" + clash.getName()
                                        + "' is already bound to this SSO principal"))));
            }
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

        // Phase E.6 (DR-014 follow-up): SSO-bound invite — when the
        // operator supplied an externalId, skip the local-password
        // generation entirely. The IdP owns the credential; the row's
        // (external_id_provider, external_id) pair is what
        // findByExternalIdentity (D.3) keys on at first SSO login.
        boolean ssoBound = body.externalId() != null && !body.externalId().trim().isEmpty();
        boolean runWs = Boolean.TRUE.equals(body.runWebservices());
        String generatedPassword = null;
        String passwordHash;
        if (ssoBound) {
            // No local password — the bean's passwd column is non-null
            // in the schema, so we write the legacy "directory-owned"
            // sentinel ("*", UserAccountBean.LDAP_PASSWORD) that the
            // restore + reset paths already use to recognise external
            // credentials.
            passwordHash = UserAccountBean.LDAP_PASSWORD;
        } else {
            generatedPassword = securityManager.genPassword();
            passwordHash = securityManager.encryptPassword(generatedPassword, runWs);
        }

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
        // Phase E.6: bind the SSO identity. external_id case is
        // preserved verbatim (SAML attribute case-sensitivity).
        // external_id_provider is auto-filled from
        // libreclinica.sso.provider.name — never exposed on the wire to
        // keep the operator UI institution-agnostic.
        if (ssoBound) {
            newUser.setExternalId(body.externalId().trim());
            newUser.setExternalIdProvider(ssoProperties.getProvider().getName());
        }

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
                true,
                false);

        Map<String, Object> response = new HashMap<>();
        response.put("user", dto);
        // SSO-bound users never carry a local password — return null
        // regardless of sendEmail. For local accounts, mirror the
        // legacy displayPwd switch: return the cleartext when the
        // admin opted to distribute manually.
        response.put("generatedPassword",
                ssoBound ? null
                         : (Boolean.FALSE.equals(body.sendEmail()) ? generatedPassword : null));
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

        // Phase E.6 (DR-014 follow-up): externalId shape — when
        // present, must look like an eppn (contain '@') and fit the
        // 255-char column. Case is preserved verbatim — SAML
        // assertions are case-sensitive per spec, lowercasing here
        // would break the lookup at first SSO login.
        if (body.externalId() != null) {
            String raw = body.externalId();
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                // Treat blank-but-present as "not SSO" — the create
                // handler's ssoBound branch only fires on non-blank,
                // so this is OK; surface no error.
            } else if (trimmed.length() > 255) {
                out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                        "externalId",
                        "External identifier must be 255 characters or fewer"));
            } else if (!trimmed.contains("@")) {
                out.add(new SubjectsApiController.ValidationErrorBody.FieldError(
                        "externalId",
                        "External identifier must look like an institutional principal (e.g. user@meduniwien.ac.at)"));
            }
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
    /* POST /api/v1/users/{username}/{disable,restore}                   */
    /*   (Phase E A7.3 — user lifecycle)                                 */
    /* ----------------------------------------------------------------- */

    /**
     * Soft-delete a user account.
     *
     * <p>Mirrors {@code DeleteUserServlet.delete} (legacy action=delete
     * branch): flips {@code user_account.status_id → DELETED}. The DAO
     * cascade ({@link UserAccountDAO#delete}) also marks every
     * {@code study_user_role} row owned by this user as DELETED via
     * {@code deleteStudyUserRolesIncludeAutoRemove}.
     *
     * <p>Sysadmin-only. Cannot disable yourself (409 — mirrors the
     * legacy "you can't suicide" guard pattern).
     */
    @PostMapping("/{username}/disable")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyUserDto.class)))
    public ResponseEntity<?> disable(@PathVariable("username") String username,
                                     HttpSession session) {
        ResponseEntity<?> guard = preflightLifecycle(session, username);
        if (guard != null) return guard;

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        UserAccountBean target = (UserAccountBean) userDao.findByUserName(username);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No user with username '" + username + "'"));
        }
        if (target.getId() == me.getId()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "You cannot disable your own account"));
        }
        if (target.getStatus() != null && target.getStatus().getId() == Status.DELETED.getId()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "User '" + username + "' is already disabled"));
        }

        target.setUpdater(me);
        userDao.delete(target);

        LOG.info("Disable user: username={} by admin={}", username, me.getName());
        // Re-load so the bean reflects the post-cascade state.
        UserAccountBean refreshed = (UserAccountBean) userDao.findByUserName(username);
        return ResponseEntity.ok(projectToStudyUserDto(refreshed, currentStudy));
    }

    /**
     * Lifecycle request body for the restore endpoint.
     *
     * <p>{@code sendEmail} mirrors the legacy {@code displayPwd} switch:
     * when {@code false} (or the body is absent) the response carries
     * the one-time password so the admin can hand it off manually. The
     * email path is queued behind the MailService extraction follow-up
     * (same gap as A7.1's create).
     */
    @Schema(name = "RestoreUserRequest")
    public record RestoreUserRequest(Boolean sendEmail) {}

    /**
     * Restore a previously-disabled user.
     *
     * <p>Mirrors {@code DeleteUserServlet.restore}: flips
     * {@code status_id → AVAILABLE}, generates a fresh one-time
     * password, sets {@code passwd_timestamp = null} (force change on
     * first login), and restores cascaded {@code study_user_role}
     * rows via the DAO's {@code restoreStudyUserRolesByUserID}.
     *
     * <p>LDAP / SSO users bypass the password generation step — the
     * directory / IdP owns the credential — but their status_id is
     * still restored. The response carries
     * {@code generatedPassword: null} in that case.
     */
    @PostMapping("/{username}/restore")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = StudyUserDto.class)))
    public ResponseEntity<?> restore(@PathVariable("username") String username,
                                     @RequestBody(required = false) RestoreUserRequest body,
                                     HttpSession session) {
        ResponseEntity<?> guard = preflightLifecycle(session, username);
        if (guard != null) return guard;

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        UserAccountBean target = (UserAccountBean) userDao.findByUserName(username);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No user with username '" + username + "'"));
        }
        if (target.getStatus() == null || target.getStatus().getId() != Status.DELETED.getId()) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "User '" + username + "' is not disabled"));
        }

        boolean isDirectoryOwned = isDirectoryOwnedCredential(target);
        String generatedPassword = null;
        if (!isDirectoryOwned) {
            generatedPassword = securityManager.genPassword();
            String hash = securityManager.encryptPassword(generatedPassword,
                    target.getRunWebservices());
            target.setPasswd(hash);
            target.setPasswdTimestamp(null);
        }

        target.setUpdater(me);
        userDao.restore(target);

        LOG.info("Restore user: username={} by admin={} directoryOwned={}",
                username, me.getName(), isDirectoryOwned);

        UserAccountBean refreshed = (UserAccountBean) userDao.findByUserName(username);
        StudyUserDto dto = projectToStudyUserDto(refreshed, currentStudy);
        Map<String, Object> response = new HashMap<>();
        response.put("user", dto);
        response.put("generatedPassword",
                (body != null && Boolean.TRUE.equals(body.sendEmail())) ? null : generatedPassword);
        return ResponseEntity.ok(response);
    }

    /**
     * Shared 401/400/403 preflight for lifecycle endpoints. Returns
     * {@code null} when the request may proceed.
     */
    private ResponseEntity<?> preflightLifecycle(HttpSession session, String username) {
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
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "username path variable is required"));
        }
        return null;
    }

    /**
     * @return {@code true} when the bean's credential is owned by an
     *         external directory / IdP — LDAP-typed users + SSO-bound
     *         users. The restore endpoint skips local password
     *         generation for these so the IdP / directory remains
     *         authoritative.
     */
    private static boolean isDirectoryOwnedCredential(UserAccountBean ua) {
        String provider = ua.getExternalIdProvider();
        if (provider != null && !provider.isBlank()) return true;
        String authtype = ua.getAuthtype();
        return authtype != null && authtype.toLowerCase().contains("ldap");
    }

    /* ----------------------------------------------------------------- */
    /* GET    /api/v1/users/{username}/roles                              */
    /* POST   /api/v1/users/{username}/roles                              */
    /* PUT    /api/v1/users/{username}/roles/{studyId}                    */
    /* DELETE /api/v1/users/{username}/roles/{studyId}                    */
    /*    (Phase E A7.5 — study-user-role assignments)                    */
    /* ----------------------------------------------------------------- */

    /**
     * Body of POST /users/{username}/roles and PUT
     * /users/{username}/roles/{studyOid}.
     *
     * <p>Multi-role: callers may submit either a single SPA role via
     * {@code role}, or a bulk set-replace via {@code roles}. PUT
     * accepts both shapes ({@code roles} wins when both are present);
     * POST treats them as additive grants (one row per role). When
     * neither {@code role} nor {@code roles} is provided the call
     * fails validation.
     */
    @Schema(name = "RoleAssignmentRequest")
    public record RoleAssignmentRequest(String studyOid, String role, List<String> roles) {}

    /**
     * List every study/role binding owned by {@code username},
     * filtered to the caller's visible study set.
     *
     * <p>Mirrors the legacy "view user's role bindings" surface that
     * the JSP admin renders on the user-detail page. Sysadmin-only.
     */
    @GetMapping("/{username}/roles")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(type = "array", implementation = RoleBindingDto.class)))
    public ResponseEntity<?> listRoles(@PathVariable("username") String username,
                                       HttpSession session) {
        ResponseEntity<?> guard = preflightLifecycle(session, username);
        if (guard != null) return guard;

        // Phase E.6 (2026-06-03): the sysadmin-only "manage roles for
        // this user" dialog must see EVERY binding the target user has
        // — including studies outside the caller's current scope. The
        // user-list endpoint at line 105 stays site-visibility-scoped
        // (that one is about "who can do work on the active study"),
        // but listRoles is about "what bindings does this user
        // already have anywhere so the dialog can offer the right
        // grant / change / revoke actions". Without all bindings the
        // dialog tries to grant a binding on a study the user already
        // has one for (e.g. the auto-coordinator binding created by
        // POST /api/v1/studies) and trips the 409 conflict guard.
        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        UserAccountBean target = (UserAccountBean) userDao.findByUserName(username);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No user with username '" + username + "'"));
        }

        ArrayList<StudyUserRoleBean> bindings = userDao.findAllRolesByUserName(username);
        StudyDAO studyDao = new StudyDAO(dataSource);
        List<RoleBindingDto> out = new ArrayList<>();
        for (StudyUserRoleBean sur : bindings) {
            out.add(toRoleBindingDto(sur, studyDao));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Grant a study/role binding to {@code username}. Mirrors
     * {@code SetUserRoleServlet:206} — the legacy gate (sysadmin
     * only) and the site-level role legality check (Coordinator /
     * StudyDirector cannot be granted at site level) are preserved.
     *
     * <p>Multi-role: the endpoint is now additive. A user may hold
     * multiple roles on the same study (e.g. CRC + Investigator);
     * each POST inserts a fresh row. 409 is reserved for the strict
     * idempotency case where the EXACT SAME (user, study, role)
     * triple is already an active grant.
     */
    @PostMapping("/{username}/roles")
    @ApiResponse(responseCode = "201",
                 content = @Content(schema = @Schema(implementation = RoleBindingDto.class)))
    public ResponseEntity<?> grantRole(@PathVariable("username") String username,
                                       @RequestBody(required = false) RoleAssignmentRequest body,
                                       HttpSession session) {
        ResponseEntity<?> guard = preflightLifecycle(session, username);
        if (guard != null) return guard;
        if (body == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Request body is required",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "body", "missing"))));
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);

        List<SubjectsApiController.ValidationErrorBody.FieldError> shapeErrors =
                validateRoleAssignmentShape(body);
        if (!shapeErrors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", shapeErrors));
        }

        UserAccountBean target = (UserAccountBean) userDao.findByUserName(username);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No user with username '" + username + "'"));
        }
        StudyBean study = (StudyBean) studyDao.findByOid(body.studyOid());
        if (study == null || study.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + body.studyOid() + "'"));
        }
        Role legacyRole = RoleMapper.fromSpaRole(body.role());
        if (!UserAdminAuthorization.roleAssignmentIsLegal(legacyRole, study)) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "role", "Role '" + body.role()
                                    + "' cannot be granted at site level — assign at the parent study"))));
        }

        // Multi-role idempotency: refuse only when an ACTIVE row already
        // exists for the EXACT (user, study, role) triple. Other active
        // grants for the same (user, study) — e.g. user already has CRC,
        // caller is granting Investigator — pass through as a new row.
        if (hasActiveGrantWithRole(userDao, username, study.getId(), legacyRole.getName())) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "User '" + username + "' already has role '" + body.role()
                            + "' on study " + body.studyOid()));
        }

        StudyUserRoleBean sur = new StudyUserRoleBean();
        sur.setStudyId(study.getId());
        sur.setRoleName(legacyRole.getName());
        sur.setStatus(Status.AVAILABLE);
        sur.setOwner(me);
        sur.setUserName(username);
        sur.setUserAccountId(target.getId());
        userDao.createStudyUserRole(target, sur);

        LOG.info("Grant role: username={} studyOid={} role={} by admin={}",
                username, body.studyOid(), legacyRole.getName(), me.getName());

        StudyUserRoleBean refreshed = userDao.findRoleByUserNameAndStudyId(username, study.getId());

        String newRoleName = legacyRole.getName();
        EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource), me, study, null,
                "User role granted — user=" + username + " role=" + newRoleName,
                "study_user_role",
                refreshed != null ? refreshed.getId() : 0,
                "role_id", "", newRoleName);

        return ResponseEntity.status(201).body(toRoleBindingDto(refreshed, studyDao));
    }

    /**
     * Multi-role check: walk every grant the user has, return true
     * if any active row matches both {@code studyId} and
     * {@code legacyRoleName}. {@link UserAccountDAO#findRoleByUserNameAndStudyId}
     * only returns the FIRST match per (user, study) which is no
     * longer sufficient now that the same pair can host multiple
     * role rows.
     */
    private static boolean hasActiveGrantWithRole(UserAccountDAO userDao,
                                                  String username,
                                                  int studyId,
                                                  String legacyRoleName) {
        for (StudyUserRoleBean r : userDao.findAllRolesByUserName(username)) {
            if (r == null || r.getStudyId() != studyId) continue;
            if (r.getStatus() == null
                    || r.getStatus().getId() != Status.AVAILABLE.getId()) continue;
            if (r.getRole() != null
                    && legacyRoleName != null
                    && legacyRoleName.equalsIgnoreCase(r.getRole().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Change the role(s) on an existing binding. Mirrors
     * {@code EditStudyUserRoleServlet}. Sysadmin-only, same
     * site-level legality check.
     *
     * <p>Two shapes:
     * <ul>
     *   <li>{@code roles}: atomic set-replace. The submitted list
     *       becomes the new active role set for (user, study);
     *       missing roles are soft-deleted (status_id = 5), new
     *       roles are inserted. No-op on roles already active.</li>
     *   <li>{@code role}: legacy single-role overwrite — kept for
     *       back-compat with callers that haven't migrated yet.
     *       Mutates the first active binding row in place.</li>
     * </ul>
     *
     * <p>Returns the refreshed role-binding list for (user, study).
     */
    @PutMapping("/{username}/roles/{studyOid}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RoleBindingDto.class)))
    public ResponseEntity<?> updateRole(@PathVariable("username") String username,
                                        @PathVariable("studyOid") String studyOid,
                                        @RequestBody(required = false) RoleAssignmentRequest body,
                                        HttpSession session) {
        ResponseEntity<?> guard = preflightLifecycle(session, username);
        if (guard != null) return guard;

        boolean bulkMode = body != null && body.roles() != null && !body.roles().isEmpty();
        if (!bulkMode) {
            if (body == null || body.role() == null || body.role().isBlank()) {
                return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                        "Validation failed",
                        List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                                "role", "Role is required"))));
            }
        }

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);

        StudyBean study = (StudyBean) studyDao.findByOid(studyOid);
        if (study == null || study.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }

        if (bulkMode) {
            return updateRolesBulk(username, body.roles(), study, studyOid, me, userDao, studyDao);
        }

        // Legacy single-role overwrite path.
        Role legacyRole = RoleMapper.fromSpaRole(body.role());
        if (legacyRole == null) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "role", "Unknown role — expected Administrator / Data Manager / CRC / Monitor / Investigator"))));
        }
        if (!UserAdminAuthorization.roleAssignmentIsLegal(legacyRole, study)) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(new SubjectsApiController.ValidationErrorBody.FieldError(
                            "role", "Role '" + body.role()
                                    + "' cannot be granted at site level — assign at the parent study"))));
        }

        StudyUserRoleBean existing = userDao.findRoleByUserNameAndStudyId(username, study.getId());
        if (existing == null || existing.getId() == 0 || !existing.isActive()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No active role binding for user '" + username + "' on study '" + studyOid + "'"));
        }

        String oldRoleName = existing.getRole() != null ? existing.getRole().getName() : "";

        existing.setRoleName(legacyRole.getName());
        existing.setStatus(Status.AVAILABLE);
        existing.setUpdater(me);
        userDao.updateStudyUserRole(existing, username);

        LOG.info("Update role: username={} studyOid={} role={} by admin={}",
                username, studyOid, legacyRole.getName(), me.getName());

        StudyUserRoleBean refreshed = userDao.findRoleByUserNameAndStudyId(username, study.getId());

        String newRoleName = refreshed != null && refreshed.getRole() != null
                ? refreshed.getRole().getName() : legacyRole.getName();
        EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource), me, study, null,
                "User role changed — user=" + username + " role=" + newRoleName,
                "study_user_role",
                refreshed != null ? refreshed.getId() : existing.getId(),
                "role_id", oldRoleName, newRoleName);

        return ResponseEntity.ok(toRoleBindingDto(refreshed, studyDao));
    }

    /**
     * Bulk set-replace for the multi-role PUT path. Reads the current
     * active grants for (user, study), diffs against the requested
     * roles, then in one logical transaction:
     *
     * <ul>
     *   <li>Inserts a fresh row per role in {@code requested - current}.</li>
     *   <li>Soft-deletes (status_id → 5) the existing row per role in
     *       {@code current - requested}.</li>
     *   <li>Leaves untouched any role that appears in both sets.</li>
     * </ul>
     *
     * <p>Audit: one {@code writeAuditEvent} row per inserted or
     * soft-deleted role, packing the username + role name into the
     * action-message text so the audit-log view can render the diff.
     */
    private ResponseEntity<?> updateRolesBulk(String username,
                                              List<String> requestedSpaRoles,
                                              StudyBean study,
                                              String studyOid,
                                              UserAccountBean me,
                                              UserAccountDAO userDao,
                                              StudyDAO studyDao) {
        // Validate every requested role; reject the bulk request if
        // any single role is unknown or illegal at the study tier.
        List<SubjectsApiController.ValidationErrorBody.FieldError> errors = new ArrayList<>();
        LinkedHashMap<String, Role> resolved = new LinkedHashMap<>();
        for (String spaRole : requestedSpaRoles) {
            if (spaRole == null || spaRole.isBlank()) continue;
            Role legacy = RoleMapper.fromSpaRole(spaRole);
            if (legacy == null) {
                errors.add(fieldError("roles",
                        "Unknown role '" + spaRole + "' — expected Administrator / Data Manager / CRC / Monitor / Investigator"));
                continue;
            }
            if (!UserAdminAuthorization.roleAssignmentIsLegal(legacy, study)) {
                errors.add(fieldError("roles", "Role '" + spaRole
                        + "' cannot be granted at site level — assign at the parent study"));
                continue;
            }
            resolved.put(legacy.getName(), legacy);
        }
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed", errors));
        }
        if (resolved.isEmpty()) {
            return ResponseEntity.badRequest().body(new SubjectsApiController.ValidationErrorBody(
                    "Validation failed",
                    List.of(fieldError("roles", "At least one role is required"))));
        }

        UserAccountBean target = (UserAccountBean) userDao.findByUserName(username);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No user with username '" + username + "'"));
        }

        // Snapshot the current active grants for (user, study), keyed by
        // legacy role-name. Multiple grants per role (shouldn't happen,
        // but the schema allows it) collapse to the first row — the rest
        // ride along as if they're being soft-deleted-and-recreated.
        ArrayList<StudyUserRoleBean> all = userDao.findAllRolesByUserName(username);
        LinkedHashMap<String, StudyUserRoleBean> currentByRole = new LinkedHashMap<>();
        for (StudyUserRoleBean r : all) {
            if (r == null || r.getStudyId() != study.getId()) continue;
            if (r.getStatus() == null
                    || r.getStatus().getId() != Status.AVAILABLE.getId()) continue;
            if (r.getRole() == null) continue;
            currentByRole.putIfAbsent(r.getRole().getName(), r);
        }

        AuditEventDAO auditDao = new AuditEventDAO(dataSource);
        int adds = 0;
        int removes = 0;

        // Adds: anything in resolved that isn't already active.
        for (Map.Entry<String, Role> e : resolved.entrySet()) {
            String legacyRoleName = e.getKey();
            if (currentByRole.containsKey(legacyRoleName)) continue;
            StudyUserRoleBean sur = new StudyUserRoleBean();
            sur.setStudyId(study.getId());
            sur.setRoleName(legacyRoleName);
            sur.setStatus(Status.AVAILABLE);
            sur.setOwner(me);
            sur.setUserName(username);
            sur.setUserAccountId(target.getId());
            userDao.createStudyUserRole(target, sur);
            EventCrfsApiController.writeAuditEvent(auditDao, me, study, null,
                    "User role granted (bulk) — user=" + username + " role=" + legacyRoleName,
                    "study_user_role", 0,
                    "role_id", "", legacyRoleName);
            adds++;
        }

        // Removes: anything currently active but absent from resolved.
        for (Map.Entry<String, StudyUserRoleBean> e : currentByRole.entrySet()) {
            String legacyRoleName = e.getKey();
            if (resolved.containsKey(legacyRoleName)) continue;
            StudyUserRoleBean row = e.getValue();
            row.setStatus(Status.DELETED);
            row.setUpdater(me);
            userDao.updateStudyUserRole(row, username);
            EventCrfsApiController.writeAuditEvent(auditDao, me, study, null,
                    "User role revoked (bulk) — user=" + username + " role=" + legacyRoleName,
                    "study_user_role", row.getId(),
                    "role_id", legacyRoleName, "");
            removes++;
        }

        LOG.info("Bulk role update: username={} studyOid={} adds={} removes={} by admin={}",
                username, studyOid, adds, removes, me.getName());

        // Response: refreshed binding list for (user, study). Includes
        // soft-deleted rows so the SPA can see the full history if it
        // wants to render a per-role active/inactive badge.
        ArrayList<StudyUserRoleBean> refreshed = userDao.findAllRolesByUserName(username);
        List<RoleBindingDto> bindings = new ArrayList<>();
        for (StudyUserRoleBean r : refreshed) {
            if (r == null || r.getStudyId() != study.getId()) continue;
            bindings.add(toRoleBindingDto(r, studyDao));
        }
        return ResponseEntity.ok(bindings);
    }

    /**
     * Revoke an existing binding (status_id → DELETED). Mirrors
     * {@code DeleteStudyUserRoleServlet:73-81}. Sysadmin-only. The
     * legacy code is a status flip + {@code updateStudyUserRole}, so
     * the same pattern works here.
     */
    @DeleteMapping("/{username}/roles/{studyOid}")
    @ApiResponse(responseCode = "200",
                 content = @Content(schema = @Schema(implementation = RoleBindingDto.class)))
    public ResponseEntity<?> revokeRole(@PathVariable("username") String username,
                                        @PathVariable("studyOid") String studyOid,
                                        HttpSession session) {
        ResponseEntity<?> guard = preflightLifecycle(session, username);
        if (guard != null) return guard;

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean study = (StudyBean) studyDao.findByOid(studyOid);
        if (study == null || study.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No study with oid '" + studyOid + "'"));
        }

        StudyUserRoleBean existing = userDao.findRoleByUserNameAndStudyId(username, study.getId());
        if (existing == null || existing.getId() == 0 || !existing.isActive()) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No active role binding for user '" + username + "' on study '" + studyOid + "'"));
        }

        String oldRoleName = existing.getRole() != null ? existing.getRole().getName() : "";

        existing.setStatus(Status.DELETED);
        existing.setUpdater(me);
        userDao.updateStudyUserRole(existing, username);

        LOG.info("Revoke role: username={} studyOid={} by admin={}",
                username, studyOid, me.getName());

        EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource), me, study, null,
                "User role revoked — user=" + username + " role=" + oldRoleName,
                "study_user_role", existing.getId(),
                "role_id", oldRoleName, "");

        return ResponseEntity.ok(toRoleBindingDto(existing, studyDao));
    }

    private static List<SubjectsApiController.ValidationErrorBody.FieldError> validateRoleAssignmentShape(
            RoleAssignmentRequest body) {
        List<SubjectsApiController.ValidationErrorBody.FieldError> out = new ArrayList<>();
        if (body.studyOid() == null || body.studyOid().isBlank()) {
            out.add(fieldError("studyOid", "studyOid is required"));
        }
        if (RoleMapper.fromSpaRole(body.role()) == null) {
            out.add(fieldError("role", "Unknown role — expected Administrator / Data Manager / CRC / Monitor / Investigator"));
        }
        return out;
    }

    private static RoleBindingDto toRoleBindingDto(StudyUserRoleBean sur, StudyDAO studyDao) {
        StudyBean study = sur.getStudyId() > 0
                ? (StudyBean) studyDao.findByPK(sur.getStudyId()) : null;
        boolean isSite = study != null && study.getParentStudyId() > 0;
        String spaRole = sur.getRole() != null
                ? RoleMapper.toSpaRole(sur.getRole().getName()) : "Investigator";
        boolean active = sur.getStatus() != null
                && sur.getStatus().getId() == Status.AVAILABLE.getId();
        return new RoleBindingDto(
                sur.getStudyId(),
                study == null ? null : study.getOid(),
                study == null ? null : study.getName(),
                isSite && study != null ? study.getName() : null,
                spaRole,
                active);
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/users/{username}/resetPassword                       */
    /*   (Phase E A7.4 — admin password reset)                           */
    /* ----------------------------------------------------------------- */

    /**
     * Request body for the resetPassword endpoint.
     *
     * <p>{@code sendEmail} mirrors the legacy {@code displayPwd}
     * switch (see A7.1 + A7.3). When the email path is not wired
     * (current state), the response always carries the cleartext
     * one-time password so the admin can distribute manually.
     */
    @Schema(name = "ResetPasswordRequest")
    public record ResetPasswordRequest(Boolean sendEmail) {}

    /**
     * Generate a fresh one-time password for an existing user and
     * force them to change it on first login.
     *
     * <p>Mirrors the {@code resetPassword=true} branch of
     * {@code EditUserAccountServlet:193–211} + the password-gen path
     * of {@code DeleteUserServlet:96–108}.
     *
     * <p>Sysadmin-only. Refuses 400 for directory-owned users (SSO +
     * LDAP) — the directory / IdP owns the credential; resetting it
     * here would create a phantom local password that fails to
     * authenticate against the IdP anyway.
     */
    @PostMapping("/{username}/resetPassword")
    @ApiResponse(responseCode = "200")
    public ResponseEntity<?> resetPassword(@PathVariable("username") String username,
                                           @RequestBody(required = false) ResetPasswordRequest body,
                                           HttpSession session) {
        ResponseEntity<?> guard = preflightLifecycle(session, username);
        if (guard != null) return guard;

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        UserAccountBean target = (UserAccountBean) userDao.findByUserName(username);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No user with username '" + username + "'"));
        }
        if (isDirectoryOwnedCredential(target)) {
            // Distinguish SSO from LDAP in the message — the admin sees
            // which external system owns the credential and can route
            // the user to the right reset workflow.
            String provider = target.getExternalIdProvider();
            String which = (provider != null && !provider.isBlank())
                    ? "the identity provider"
                    : "the directory";
            return ResponseEntity.badRequest().body(Map.of("message",
                    "User '" + username + "' is authenticated via " + which
                            + " — password resets must go through that workflow"));
        }
        if (target.getStatus() != null
                && target.getStatus().getId() == Status.DELETED.getId()) {
            // Resetting a disabled user's password is meaningless —
            // they can't log in until they're restored.
            return ResponseEntity.status(409).body(Map.of("message",
                    "User '" + username + "' is disabled — restore them before resetting their password"));
        }

        String generatedPassword = securityManager.genPassword();
        String hash = securityManager.encryptPassword(generatedPassword,
                target.getRunWebservices());
        target.setPasswd(hash);
        target.setPasswdTimestamp(null);
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        userDao.update(target);

        // Audit row: log the event WITHOUT either side of the password.
        // Mirrors SubjectsApiController's sign-endpoint redaction
        // pattern — passwords never enter the audit_log_event columns.
        try {
            AuditEventDAO auditDAO = new AuditEventDAO(dataSource);
            AuditEventBean ae = new AuditEventBean();
            ae.setUserId(me.getId());
            ae.setAuditTable("user_account");
            ae.setEntityId(target.getId());
            ae.setColumnName("passwd");
            ae.setOldValue("");
            ae.setNewValue("");
            ae.setActionMessage("password_reset by admin " + me.getName()
                    + " for user " + target.getName());
            auditDAO.create(ae);
        } catch (Exception e) {
            LOG.warn("Audit write failed for password_reset username={} (continuing): {}",
                    username, e.getMessage());
        }

        LOG.info("Reset password: username={} by admin={}", username, me.getName());

        boolean returnPassword = body == null || !Boolean.TRUE.equals(body.sendEmail());
        Map<String, Object> response = new HashMap<>();
        response.put("generatedPassword", returnPassword ? generatedPassword : null);
        return ResponseEntity.ok(response);
    }

    /* ----------------------------------------------------------------- */
    /* POST /api/v1/users/{username}/unlock                               */
    /*   (Phase E.6 unlock-user — clear lock + issue fresh OTP)           */
    /* ----------------------------------------------------------------- */

    /**
     * Request body for the unlock endpoint.
     *
     * <p>Shape mirrors {@link ResetPasswordRequest}: the cleartext
     * one-time password rides back in the response when
     * {@code sendEmail != true} so the admin can hand it off manually
     * until the MailService extraction lands.
     */
    @Schema(name = "UnlockUserRequest")
    public record UnlockUserRequest(Boolean sendEmail) {}

    /**
     * Clear a locked-out user's account: flip
     * {@code account_non_locked} back to {@code true}, reset
     * {@code lock_counter} to 0, and issue a fresh one-time password
     * so the user can log in once and be force-changed.
     *
     * <p>Mirrors {@code ResetUserAccountServlet:46–104} (legacy admin
     * surface) — except the legacy servlet's audit row is replaced
     * with a typed {@code audit_log_event} row keyed on
     * {@code account_non_locked} so the audit trail is queryable per
     * column (DR-009).
     *
     * <p>Sysadmin-only (preflightLifecycle). Refuses 400 for
     * directory-owned credentials (SSO + LDAP) — the IdP / directory
     * owns the credential, so resetting it here would create a
     * phantom local password. Refuses 409 if the account is not
     * currently locked — the admin needs a clear signal that the
     * lock was already cleared (race with another admin or self-
     * unlock via successful login).
     *
     * <p>Status codes: {@code 200} on success (returns
     * {@code {user: StudyUserDto, generatedPassword: String|null}}),
     * {@code 400} no active study OR SSO/LDAP, {@code 401}
     * unauthenticated, {@code 403} non-sysadmin, {@code 404} unknown
     * username, {@code 409} not currently locked.
     */
    @PostMapping("/{username}/unlock")
    @ApiResponse(responseCode = "200")
    public ResponseEntity<?> unlock(@PathVariable("username") String username,
                                    @RequestBody(required = false) UnlockUserRequest body,
                                    HttpSession session) {
        ResponseEntity<?> guard = preflightLifecycle(session, username);
        if (guard != null) return guard;

        UserAccountBean me = (UserAccountBean) session.getAttribute("userBean");
        StudyBean currentStudy = (StudyBean) session.getAttribute("study");
        UserAccountDAO userDao = new UserAccountDAO(dataSource);
        UserAccountBean target = (UserAccountBean) userDao.findByUserName(username);
        if (target == null || target.getId() == 0) {
            return ResponseEntity.status(404).body(Map.of("message",
                    "No user with username '" + username + "'"));
        }
        if (isDirectoryOwnedCredential(target)) {
            String provider = target.getExternalIdProvider();
            String which = (provider != null && !provider.isBlank())
                    ? "the identity provider"
                    : "the directory";
            return ResponseEntity.badRequest().body(Map.of("message",
                    "User '" + username + "' is authenticated via " + which
                            + " — lock state is owned by that workflow"));
        }
        // 409 when not currently locked. account_non_locked may be
        // {@code null} on legacy rows that predate the column default
        // — treat null as "not locked" (the loginAdvice path defaults
        // to true), per parity with SpringSecurity's check.
        if (!Boolean.FALSE.equals(target.getAccountNonLocked())) {
            return ResponseEntity.status(409).body(Map.of("message",
                    "User '" + username + "' is not currently locked"));
        }

        String generatedPassword = securityManager.genPassword();
        String hash = securityManager.encryptPassword(generatedPassword,
                target.getRunWebservices());
        target.setPasswd(hash);
        target.setPasswdTimestamp(null);
        target.setAccountNonLocked(Boolean.TRUE);
        target.setLockCounter(0);
        target.setUpdater(me);
        target.setUpdatedDate(new java.util.Date());
        userDao.update(target);

        // Audit row: keyed on account_non_locked (the column the admin
        // flipped). Phase E.6.ci: write straight to audit_log_event
        // instead of routing through {@link AuditEventDAO#create},
        // which only persists {@code audit_table / user_id / entity_id
        // / reason_for_change / action_message} and drops the typed
        // {@code old_value / new_value / entity_name / audit_log_event_type_id}
        // fields the SPA's Audit Log view + the unlock IT both read.
        // Same shape as {@link MeApiController#emitProfileAudit} — column
        // name rides in {@code entity_name} (audit_log_event has no
        // {@code column_name} column, that's audit_event_values).
        emitUnlockAudit(me.getId(), target.getId());

        LOG.info("Unlock user: username={} by admin={}", username, me.getName());

        boolean returnPassword = body == null || !Boolean.TRUE.equals(body.sendEmail());
        Map<String, Object> response = new HashMap<>();
        response.put("user", projectToStudyUserDto(target, currentStudy));
        response.put("generatedPassword", returnPassword ? generatedPassword : null);
        return ResponseEntity.ok(response);
    }

    /**
     * Phase E.6.ci — emit a typed {@code audit_log_event} row for the
     * unlock action. Same SQL shape as
     * {@link MeApiController#emitProfileAudit}: column name lands in
     * {@code entity_name} (audit_log_event has no {@code column_name}
     * column), pre/post values land in {@code old_value / new_value}.
     *
     * <p>Reuses {@code audit_log_event_type_id = 50}
     * ({@code user_account_profile_updated}) — the unlock is a
     * user_account state mutation, lifecycle-adjacent to the SPA's
     * own profile-edit audit rows, and the existing dictionary row
     * already carries a sensible display name for the Audit Log view.
     * Minting a dedicated type id would force a Liquibase changeset
     * for cosmetic differentiation.
     *
     * <p>Failures are swallowed: the unlock itself has already
     * persisted (account_non_locked = true), so a missed audit row
     * should NOT roll back the admin's lifecycle change.
     */
    private void emitUnlockAudit(int adminUserId, int targetUserId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'user_account', ?, ?, ?, ?)")) {
            ps.setInt(1, 50); // user_account_profile_updated
            ps.setInt(2, adminUserId);
            ps.setInt(3, targetUserId);
            ps.setString(4, "account_non_locked");
            ps.setString(5, "false");
            ps.setString(6, "true");
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to write audit_log_event row for unlock target={} admin={}: {}",
                    targetUserId, adminUserId, e.getMessage());
        }
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
        boolean locked = Boolean.FALSE.equals(ua.getAccountNonLocked());
        return new StudyUserDto(
                String.valueOf(ua.getId()),
                nullToEmpty(ua.getName()),
                displayName(ua),
                blankToNull(ua.getEmail()),
                spaRole,
                siteLabel,
                authForUser(ua),
                lastLogin,
                active,
                locked);
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
