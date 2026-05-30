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
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.ImportResource;

/**
 * Spring Boot entrypoint. Extends {@link SpringBootServletInitializer} so
 * the WAR's root {@code WebApplicationContext} is created by Boot
 * (Servlet 3.0+ {@code WebApplicationInitializer} SPI). External Tomcat
 * deployment is the current form; {@link #main(String[])} runs the stack
 * via {@code java -jar} once packaging flips to executable.
 *
 * <p>Phase C.14 cliff (2026-05-30): replaces the legacy
 * {@code OCContextLoaderListener} + {@code contextConfigLocation}
 * web.xml bootstrap. {@link config.ServletInfraConfig} owns the servlet
 * listeners + non-security filters; {@link config.SecurityConfig} owns
 * the {@code SecurityFilterChain @Bean}.
 *
 * <p><strong>Scoped @ComponentScan:</strong> {@code scanBasePackages = ".config"}
 * restricts Boot's auto-scan to {@code .config} only. Default scan from this
 * class's package would pull controllers into the ROOT context, but their
 * {@code @Autowired @Qualifier("sidebarInit")} dependencies live in the
 * DispatcherServlet CHILD context (pages-servlet.xml). The legacy
 * {@code <context:component-scan>} directives in
 * {@code applicationContext-core-spring.xml} (services) and
 * {@code pages-servlet.xml} (controllers) stay authoritative.
 *
 * <p><strong>Autoconfig exclusions:</strong> the XML bean files declare the
 * same beans Boot would auto-configure. {@code SecurityFilterAutoConfiguration}
 * stays excluded — wait, it doesn't: Boot's auto-registration of the
 * {@code springSecurityFilterChain} DelegatingFilterProxy IS needed (it's
 * how the {@code SecurityFilterChain @Bean} in {@link config.SecurityConfig}
 * gets hooked into the servlet chain). Excluding it would mean no auth.
 *
 * @see <a href="../../../../../../docs/development/modernization/phase-c-execution-playbook.md">Phase C playbook</a>
 */
@SpringBootApplication(
        scanBasePackages = "at.ac.meduniwien.ophthalmology.libreclinica.config",
        exclude = {
                // C.3 removes: DataSourceAutoConfiguration + DataSourceTransactionManagerAutoConfiguration
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                // C.4 removes: HibernateJpaAutoConfiguration
                HibernateJpaAutoConfiguration.class,
                // C.5 removes: QuartzAutoConfiguration
                QuartzAutoConfiguration.class,
                // C.7 removes: MailSenderAutoConfiguration
                MailSenderAutoConfiguration.class,
                // C.10/C.14: applicationContext-core-security.xml provides
                // ocUserDetailsService; UserDetailsServiceAutoConfiguration
                // excluded to stop Boot's generated-password banner.
                UserDetailsServiceAutoConfiguration.class,
                // SecurityAutoConfiguration is NOT excluded — Boot needs to
                // provide the DelegatingFilterProxyRegistrationBean for
                // springSecurityFilterChain. SecurityFilterAutoConfiguration
                // would auto-register a SECOND DelegatingFilterProxy though —
                // exclude that one specifically.
                SecurityFilterAutoConfiguration.class,
                // C.13 removes: LiquibaseAutoConfiguration
                LiquibaseAutoConfiguration.class,
                // Phase C.14: LdapAutoConfiguration tries to wire
                // ObjectDirectoryMapper using ConverterUtils which is not in
                // our pinned spring-ldap version. LDAP usage is via
                // contextSource + ldapAuthenticationProvider XML beans.
                LdapAutoConfiguration.class,
                // Phase C.14 kept DispatcherServletAutoConfiguration +
                // WebMvcAutoConfiguration + ErrorMvcAutoConfiguration
                // excluded so the legacy `pages` DispatcherServlet was
                // the sole MVC dispatcher (Boot's auto-registered one at
                // `/` would have collided with 222 legacy servlet paths).
                //
                // Phase C.15 + C.16 (2026-05-30): un-excluded. The 215
                // legacy servlets now register at exact URL patterns via
                // LegacyServletRegistry's ServletContextInitializer — per
                // servlet-spec mapping precedence, exact matches and the
                // /pages/* path-prefix beat Boot's `/`. Boot's dispatcher
                // catches only unmapped URLs (typically /actuator/* and
                // /error). WebMvcConfig was moved to `.webmvc` so Boot's
                // scanBasePackages = ".config" doesn't pick it up (it's
                // meant only for the pages DispatcherServlet child
                // context, loaded via pages-servlet.xml stub).
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
        // Phase C.6 (2026-05-30): application-context-web-beans.xml (note the
        // dash variant in the filename) was a near-exact duplicate of
        // applicationContext-web-beans.xml — both defined the sdvUtil bean.
        // The legacy ContextLoaderListener with permissive overriding
        // silently tolerated the duplicate; Boot 3.x's
        // allow-bean-definition-overriding=false rejects it.
})
public class LibreClinicaApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(LibreClinicaApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(LibreClinicaApplication.class, args);
    }
}
