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
import java.sql.SQLException;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestSourceService;

/**
 * DR-040 phase 2 — whose request a device request is, once it carries an
 * uploader token.
 *
 * <p>{@code UploaderTokenFilter} leaves the {@link IngestSourceService.Source}
 * of a tokened request on the request; the controllers pass it down and ask
 * here where a file they are about to write comes from. Without a source
 * nothing is attributed: the request is an untokened one the front door let in
 * (exactly one active location), and it does what it always did.
 */
final class DeviceScope {

    private DeviceScope() {}

    /**
     * Where a file delivered by a device request comes from: the source's
     * location and the source, or, with no source, the only active location
     * (null while several exist) and no source. A staff upload does not use this;
     * it keeps {@link LocationScope#originForStaffUpload}.
     */
    record Origin(Integer locationId, Long ingestSourceId) {

        static Origin of(Connection c, IngestSourceService.Source source) throws SQLException {
            return source != null
                    ? new Origin(source.locationId(), source.id())
                    : new Origin(LocationScope.defaultLocationId(c), null);
        }
    }
}
