/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import java.util.HashMap;
import java.util.Map;

import at.ac.meduniwien.ophthalmology.libreclinica.core.LegacyMd5Sha1PasswordEncoder;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.AuditUserLoginDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.audit.LoginAuditService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.PasswordRehashService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Phase D.1 (DR-015): replaces the legacy {@code OpenClinicaPasswordEncoder}
 * with Spring Security's {@link DelegatingPasswordEncoder}.
 *
 * <p>Hash-format prefix routing:
 * <ul>
 *   <li>{@code {bcrypt}…} → {@link BCryptPasswordEncoder} (cost 10).
 *       The new write format. All passwords created or rotated after
 *       D.1 land in this shape.</li>
 *   <li>Default (no {@code {prefix}}) →
 *       {@link LegacyMd5Sha1PasswordEncoder}. Reads existing
 *       {@code {salt}<hex>} rows produced by upstream OpenClinica /
 *       pre-D.1 LibreClinica. {@code encode()} throws — D.1 forbids
 *       writing new legacy hashes.</li>
 * </ul>
 *
 * <p><strong>Lazy bcrypt rehash:</strong>
 * {@code LegacyMd5Sha1PasswordEncoder.upgradeEncoding()} returns true
 * unconditionally, so on every successful legacy match the
 * {@code DelegatingPasswordEncoder} signals upgrade-needed. The
 * production auth filter
 * ({@code OpenClinicaUsernamePasswordAuthenticationFilter.successfulAuthentication})
 * observes the signal and dispatches to
 * {@code PasswordRehashService.rehashAfterSuccessfulLogin(userId, plaintext)},
 * which UPDATEs {@code user_account.passwd} with a fresh
 * {@code {bcrypt}…} hash. The legacy hash population drains over time
 * as users log in.
 *
 * <p><strong>Bean ID alias:</strong> {@code passwordEncoder} is the
 * canonical name; {@code openClinicaPasswordEncoder} is kept as an
 * alias so the existing
 * {@code applicationContext-core-security.xml} {@code <security:password-encoder
 * ref="openClinicaPasswordEncoder"/>} reference continues to resolve
 * during the D.1 → D-Sec closure transition. The alias drops when the
 * security XML retires.
 *
 * <p><strong>Bcrypt cost factor:</strong> 10 is Spring Security's
 * default. On modern x86 hardware, cost-10 verification is ~60–120ms;
 * acceptable for one-per-login latency. Re-tune by replacing the
 * constructor argument and rolling production with shadowed
 * verification timing.
 *
 * @see <a href="../../../../../../../docs/development/modernization/decision-record.md#dr-015--password-encoder-migration-md5sha-1--bcrypt-via-delegatingpasswordencoder">DR-015</a>
 * @see <a href="../../../../../../../docs/development/modernization/phase-d-execution-playbook.md">Phase D playbook §D.1</a>
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean(name = {"passwordEncoder", "openClinicaPasswordEncoder"})
    public PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);

        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("bcrypt", bcrypt);

        DelegatingPasswordEncoder delegating =
                new DelegatingPasswordEncoder("bcrypt", encoders);

        // Hashes without a {prefix} default to the legacy encoder.
        // This is the "DB has billions of rows in {salt}<hex> shape"
        // path — Spring Security's standard fallback for migrations.
        delegating.setDefaultPasswordEncoderForMatches(new LegacyMd5Sha1PasswordEncoder());

        return delegating;
    }

    /**
     * Phase D.1.b: lazy bcrypt rehash service. Wired into the auth
     * filter ({@code applicationContext-security.xml} myFilter bean);
     * fires after a successful authentication to detect legacy
     * hashes and rewrite them as bcrypt.
     */
    @Bean
    public PasswordRehashService passwordRehashService(
            PasswordEncoder passwordEncoder,
            UserAccountDAO userAccountDao) {
        return new PasswordRehashService(passwordEncoder, userAccountDao);
    }

    /**
     * Phase D.5 (DR-014): shared audit-write hook for all auth
     * paths (local username/password, LDAP, SSO pre-auth). Replaces
     * the inline auditUserLogin() private method on
     * OpenClinicaUsernamePasswordAuthenticationFilter so the
     * audit-trail contract holds uniformly across both the existing
     * local path and the new SSO pre-auth path.
     */
    @Bean
    public LoginAuditService loginAuditService(AuditUserLoginDao auditUserLoginDao) {
        return new LoginAuditService(auditUserLoginDao);
    }
}
