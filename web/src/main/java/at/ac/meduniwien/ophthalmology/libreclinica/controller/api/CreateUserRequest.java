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
        Boolean sendEmail
) {}
