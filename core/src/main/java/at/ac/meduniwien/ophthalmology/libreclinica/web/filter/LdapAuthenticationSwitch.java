/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * Puts the LDAP authentication provider behind the {@code ldap.enabled}
 * setting.
 *
 * <p>The provider used to sit in the authentication manager and in the
 * {@code SecurityManager}'s password-check list unconditionally, so with
 * {@code ldap.enabled=false} (the default, and the MUW setting) every failed
 * local login still went on to a bind attempt against whatever
 * {@code ldap.host} the properties name. Disabled, this wrapper takes no part
 * in authentication; enabled, it is the delegate, unchanged.
 *
 * <p>Disabled it both declines ({@link #supports} is false, so a
 * {@code ProviderManager} skips it) and, if called directly as the
 * {@code SecurityManager} does, fails with an {@link AuthenticationException}
 * rather than returning null — that caller treats any return as a successful
 * password check.
 */
public class LdapAuthenticationSwitch implements AuthenticationProvider {

    private final boolean enabled;
    private final AuthenticationProvider delegate;

    /**
     * @param enabledFlag the {@code ldap.enabled} value; LDAP is on only for
     *                    {@code true} (any case, surrounding blanks ignored)
     * @param delegate    the real LDAP provider
     */
    public LdapAuthenticationSwitch(String enabledFlag, AuthenticationProvider delegate) {
        this.enabled = enabledFlag != null && Boolean.parseBoolean(enabledFlag.trim());
        this.delegate = delegate;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!enabled) {
            throw new ProviderNotFoundException("LDAP authentication is disabled (ldap.enabled=false)");
        }
        return delegate.authenticate(authentication);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return enabled && delegate.supports(authentication);
    }
}
