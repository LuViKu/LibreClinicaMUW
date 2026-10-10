/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody.FieldError;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;

/**
 * DR-040 — which location a new study or site is created at, from the
 * request.
 *
 * <p>The request may name one ({@code locationId}). It has to be an AVAILABLE
 * location the caller may use: any, for a system administrator; for anybody
 * else one of their own. A location that does not exist, is removed, or is not
 * the caller's is the same answer, so the field is not an oracle for the
 * places other people work at.
 *
 * <p>When the request names none, the only AVAILABLE location is used. Once
 * there are several, "the" location is no longer a fact the server knows, and
 * a study created without one would be nobody's: the field is required.
 */
final class LocationChoice {

    private LocationChoice() {}

    /** The location to create at, or the field error to answer with. Exactly one is set. */
    record Result(Integer locationId, FieldError error) {}

    static Result resolve(DataSource dataSource, UserAccountBean me, Integer requested) {
        LocationScope scope = new LocationScope(dataSource);
        if (requested != null) {
            boolean usable = requested > 0 && scope.isAvailable(requested)
                    && (me != null && (me.isSysAdmin() || scope.visibleLocations(me).contains(requested)));
            return usable ? new Result(requested, null)
                    : new Result(null, new FieldError("locationId", "Unknown or unavailable location"));
        }
        Integer only = scope.defaultLocationId();
        if (only != null) return new Result(only, null);
        return new Result(null, new FieldError("locationId", scope.availableCount() == 0
                ? "No active location exists; create one under System > Standorte first"
                : "A location is required: choose one of the active locations"));
    }
}
