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

/**
 * Phase E A7.1 — POST /api/v1/users request body.
 *
 * <p>Fields mirror the legacy {@code CreateUserAccountServlet} form
 * inputs (servlet lines 197–216). Validation rules are enforced
 * server-side; the SPA also runs them client-side for fast feedback.
 *
 * <p>{@code studyId} + {@code role} encode the initial study/role
 * binding bundled with the user create (legacy
 * {@code CreateUserAccountServlet:359–372}). Subsequent role
 * assignments use the A7.5 endpoints.
 *
 * <p>{@code sendEmail} mirrors the legacy {@code displayPwd} switch:
 * <ul>
 *   <li>{@code true} — send the welcome email and don't return the
 *       cleartext password. (M1 caveat: email delivery is deferred to
 *       the {@code MailService} extraction follow-up; the response
 *       still returns {@code generatedPassword=null} in this case so
 *       the SPA knows not to display it.)</li>
 *   <li>{@code false} — return the cleartext one-time password so the
 *       admin can distribute it manually.</li>
 * </ul>
 *
 * <p>Phase E.6 (DR-014 follow-up): {@code externalId} pre-binds the
 * new row to an institutional SSO principal (Shibboleth eppn for the
 * MUW deployment). When non-blank the create handler:
 * <ol>
 *   <li>writes {@code external_id} + {@code external_id_provider}
 *       (the latter auto-filled server-side from
 *       {@code libreclinica.sso.provider.name}) so
 *       {@code findByExternalIdentity} matches on first SSO login,</li>
 *   <li>skips local password generation — the IdP owns the credential —
 *       and returns {@code generatedPassword: null} regardless of
 *       {@code sendEmail}.</li>
 * </ol>
 * eppn case is preserved verbatim (SAML attribute case-sensitivity);
 * the wire contract is the eppn alone — the provider namespace is
 * never exposed to the SPA to keep the operator UI institution-agnostic.
 */
@Schema(name = "CreateUserRequest")
public record CreateUserRequest(
        String username,
        String firstName,
        String lastName,
        String email,
        String institutionalAffiliation,
        String phone,
        Integer studyId,
        String role,
        String userType,
        String userSource,
        String authtype,
        Boolean runWebservices,
        Boolean sendEmail,
        String externalId
) {}
