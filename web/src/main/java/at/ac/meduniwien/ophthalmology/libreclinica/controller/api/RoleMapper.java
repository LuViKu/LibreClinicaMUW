/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;

/**
 * Phase E.4 M1 — translation from the legacy {@link Role} taxonomy
 * (7 IDs: invalid / admin / coordinator / director / investigator /
 * RA / monitor / RA2) into the SPA's 5-value {@code UserRole} union
 * (Investigator / Monitor / Data Manager / Administrator / CRC).
 *
 * <p>The SPA's union was chosen for end-user clarity; the legacy
 * Role table cannot be collapsed in place because audit-log entries
 * already reference the seven legacy IDs. The mapping below is
 * therefore one-way (backend → SPA); the SPA never writes its
 * shortened role string back without resolving it through this same
 * mapper on the way in.
 */
final class RoleMapper {
    private RoleMapper() {}

    /** Map a legacy role name (Role.getName()) to the SPA UserRole union. */
    static String toSpaRole(String legacyRoleName) {
        if (legacyRoleName == null) return "Investigator";
        return switch (legacyRoleName) {
            case "admin" -> "Administrator";
            case "director" -> "Data Manager";
            case "coordinator" -> "CRC";
            case "monitor" -> "Monitor";
            case "Investigator", "ra", "ra2" -> "Investigator";
            default -> "Investigator";
        };
    }

    /** Map by legacy role id. */
    static String toSpaRole(int legacyRoleId) {
        return toSpaRole(Role.get(legacyRoleId).getName());
    }
}
