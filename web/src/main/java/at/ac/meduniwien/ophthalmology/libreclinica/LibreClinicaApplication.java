/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.ImportResource;

/**
 * Phase C.2 — Spring Boot entrypoint for the future {@code java -jar}
 * execution path (C.14 flips packaging from WAR to JAR and switches the
 * Dockerfile/compose to invoke this class directly).
 *
 * <p>Until then this class is <strong>dormant during WAR deployment</strong>:
 * it does NOT extend {@code SpringBootServletInitializer}, so the
 * {@code ServletContainerInitializer} SPI doesn't auto-discover it. The
 * legacy {@code OCContextLoaderListener} declared in {@code web.xml}
 * continues to bootstrap the Spring context. The class is here so that:
 * <ul>
 *   <li>Subsequent C sub-phases (C.3-C.10) can add a {@code @Bean} method
 *       at a time to this configuration in lockstep with retiring the
 *       corresponding XML config — both paths reference the same
 *       configuration class.</li>
 *   <li>C.14 only needs to flip the packaging + add the
 *       {@code SpringBootServletInitializer} hook (one liner) to activate
 *       embedded-Tomcat boot.</li>
 * </ul>
 *
 * <p>Autoconfigs explicitly excluded below: the XML bean files declare
 * the same beans Boot would auto-configure (DataSource, JPA, Security,
 * Quartz, Mail, Liquibase). Letting both wire the bean graph would
 * produce duplicates / circular dependencies. The exclusion list shrinks
 * by one entry every time the corresponding sub-phase converts that
 * config slice from XML to Java/Boot.
 *
 * @see <a href="../../../../../../docs/development/modernization/phase-c-execution-playbook.md">Phase C playbook</a>
 */
@SpringBootApplication(exclude = {
        // C.3 removes: DataSourceAutoConfiguration + DataSourceTransactionManagerAutoConfiguration
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        // C.4 removes: HibernateJpaAutoConfiguration
        HibernateJpaAutoConfiguration.class,
        // C.5 removes: QuartzAutoConfiguration
        QuartzAutoConfiguration.class,
        // C.7 removes: MailSenderAutoConfiguration
        MailSenderAutoConfiguration.class,
        // C.10 removes: SecurityAutoConfiguration + UserDetailsServiceAutoConfiguration
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        // C.13 removes: LiquibaseAutoConfiguration
        LiquibaseAutoConfiguration.class,
})
@ImportResource({
        "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-db.xml",
        "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-email.xml",
        "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-hibernate.xml",
        "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-scheduler.xml",
        "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-security.xml",
        "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-service.xml",
        "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-spring.xml",
        "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-timer.xml",
        "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-security.xml",
        "classpath:at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-web-beans.xml",
        "classpath:at/ac/meduniwien/ophthalmology/libreclinica/application-context-web-beans.xml",
})
public class LibreClinicaApplication {

    public static void main(String[] args) {
        SpringApplication.run(LibreClinicaApplication.class, args);
    }
}
