/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import at.ac.meduniwien.ophthalmology.libreclinica.core.ExtendedBasicDataSource;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationModeStartup;

/**
 * DR-040 — reads the deployment's location mode
 * ({@code LIBRECLINICA_DEPLOYMENT_LOCATIONS}, default {@code single}) once,
 * after Liquibase, and makes it {@link LocationMode#current()}. An unknown
 * value, or single mode on a database with several active locations, fails the
 * context: the application does not start, and the log says why.
 */
@Configuration
public class LocationModeConfig {

    @Bean
    @DependsOn("liquibase")
    public LocationModeStartup.Result locationMode(
            @Value("${" + LocationMode.PROPERTY + ":single}") String raw,
            ExtendedBasicDataSource dataSource) {
        LocationMode mode;
        try {
            mode = LocationMode.parse(raw);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException(unknown.getMessage(), unknown);
        }
        LocationModeStartup.Result result = LocationModeStartup.apply(dataSource, mode);
        LocationMode.set(mode);
        return result;
    }
}
