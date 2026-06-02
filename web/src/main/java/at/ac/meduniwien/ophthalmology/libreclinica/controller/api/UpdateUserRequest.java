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
 * Phase E A7.2 — PUT /api/v1/users/{username} request body.
 *
 * <p>Mirrors {@code EditUserAccountServlet:114–191} — fields the
 * sysadmin may edit on an existing user account.
 *
 * <p>Every field is optional: {@code null} means "leave unchanged".
 * An empty string means "clear" (for phone / authtype, the only
 * legitimately-empty columns). The controller diffs each field
 * separately and writes one {@code audit_log_event} row per change.
 *
 * <p>Not editable via this endpoint:
 * <ul>
 *   <li>{@code username} — identity; rename is unsupported (legacy parity)</li>
 *   <li>{@code password} — covered by A7.4 ({@code POST /resetPassword})</li>
 *   <li>{@code accountNonLocked} — set by failed-login counter; not user-editable</li>
 *   <li>{@code status} — covered by A7.3 ({@code POST /{disable,restore}})</li>
 *   <li>{@code studyId / role} — covered by A7.5 (role assignments)</li>
 * </ul>
 */
@Schema(name = "UpdateUserRequest")
public record UpdateUserRequest(
        String firstName,
        String lastName,
        String email,
        String phone,
        String institutionalAffiliation,
        String userType,
        String authtype,
        Boolean runWebservices
) {}
