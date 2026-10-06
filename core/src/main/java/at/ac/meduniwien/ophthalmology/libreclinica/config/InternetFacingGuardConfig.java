/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.InternetFacingStartupGuard;

/**
 * Registers {@link InternetFacingStartupGuard}. The guard is a no-op unless
 * {@code libreclinica.deployment.internet-facing=true}.
 */
@Configuration
public class InternetFacingGuardConfig {

    @Bean
    public static InternetFacingStartupGuard internetFacingStartupGuard(
            DataSource dataSource,
            PasswordEncoder passwordEncoder,
            @Value("${libreclinica.deployment.internet-facing:false}") boolean internetFacing) {
        return new InternetFacingStartupGuard(new JdbcTemplate(dataSource), passwordEncoder, internetFacing);
    }
}
