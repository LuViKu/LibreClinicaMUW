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
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;

/**
 * DR-040 phase 4 — the location a study or site is at, for its
 * {@link StudyIdentityDto}: read once per request for all the rows it labels.
 * A failed read labels nothing rather than failing the answer.
 */
final class LocationLabels {

    private static final Logger LOG = LoggerFactory.getLogger(LocationLabels.class);

    private LocationLabels() {}

    static Map<Integer, LocationScope.Place> load(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            return LocationScope.places(c);
        } catch (SQLException | RuntimeException e) {
            LOG.warn("could not read the locations to label studies with: {}", e.getMessage());
            return Map.of();
        }
    }

    /** The location with this id, or null for none (0) or one that cannot be read. */
    static LocationScope.Place of(Map<Integer, LocationScope.Place> places, int locationId) {
        return locationId > 0 ? places.get(locationId) : null;
    }
}
