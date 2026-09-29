/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.search.LdapUserSearch;

import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;

/**
 * LDAP authentication is inert unless {@code ldap.enabled=true}.
 */
public class LdapAuthenticationSwitchTest {

    private static final UsernamePasswordAuthenticationToken LOGIN =
            new UsernamePasswordAuthenticationToken("someone", "guess");

    private AuthenticationProvider ldap;
    private Authentication ldapSaysYes;

    @Before
    public void setUp() {
        ldap = mock(AuthenticationProvider.class);
        ldapSaysYes = new UsernamePasswordAuthenticationToken("someone", null,
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        when(ldap.supports(any())).thenReturn(true);
        when(ldap.authenticate(any())).thenReturn(ldapSaysYes);
    }

    @Test
    public void disabledItTakesNoPartInTheAuthenticationManager() {
        LdapAuthenticationSwitch sw = new LdapAuthenticationSwitch("false", ldap);
        assertFalse(sw.supports(UsernamePasswordAuthenticationToken.class));

        try {
            new ProviderManager(sw).authenticate(LOGIN);
            fail("no provider should have accepted the login");
        } catch (AuthenticationException expected) {
            // ProviderNotFoundException: nothing supports the token
        }
        verify(ldap, never()).authenticate(any());
    }

    @Test
    public void disabledItCannotConfirmAPasswordForTheSecurityManager() {
        // SecurityManager calls every provider directly and takes any return
        // value as a verified password.
        AuthenticationProvider local = mock(AuthenticationProvider.class);
        when(local.authenticate(any())).thenThrow(new BadCredentialsException("wrong password"));
        SecurityManager securityManager = new SecurityManager();
        securityManager.setProviders(new AuthenticationProvider[] {
                new LdapAuthenticationSwitch("false", ldap), local});
        UserDetails user = User.withUsername("someone").password("x").authorities("ROLE_USER").build();

        assertFalse(securityManager.verifyPassword("guess", user));
        verify(ldap, never()).authenticate(any());
    }

    @Test
    public void enabledItIsTheLdapProviderUnchanged() {
        LdapAuthenticationSwitch sw = new LdapAuthenticationSwitch("true", ldap);

        assertTrue(sw.supports(UsernamePasswordAuthenticationToken.class));
        assertSame(ldapSaysYes, sw.authenticate(LOGIN));
        assertSame(ldapSaysYes, new ProviderManager(sw).authenticate(LOGIN));
    }

    @Test
    public void onlyTrueSwitchesItOn() {
        assertTrue(new LdapAuthenticationSwitch("true", ldap).isEnabled());
        assertTrue(new LdapAuthenticationSwitch(" TRUE ", ldap).isEnabled());
        assertFalse(new LdapAuthenticationSwitch(null, ldap).isEnabled());
        assertFalse(new LdapAuthenticationSwitch("", ldap).isEnabled());
        assertFalse(new LdapAuthenticationSwitch("false", ldap).isEnabled());
        assertFalse(new LdapAuthenticationSwitch("yes", ldap).isEnabled());
        assertFalse(new LdapAuthenticationSwitch("${ldap.enabled}", ldap).isEnabled());
    }

    @Test
    public void theSecurityContextKeepsLdapOffUnlessEnabled() {
        AuthenticationProvider unset = wiredLdapProvider(null);
        assertTrue(unset instanceof LdapAuthenticationSwitch);
        assertFalse("no ldap.enabled property means off", ((LdapAuthenticationSwitch) unset).isEnabled());
        assertFalse(((LdapAuthenticationSwitch) wiredLdapProvider("false")).isEnabled());
        assertFalse(wiredLdapProvider("false").supports(UsernamePasswordAuthenticationToken.class));

        AuthenticationProvider on = wiredLdapProvider("true");
        assertTrue(((LdapAuthenticationSwitch) on).isEnabled());
        assertTrue(on.supports(UsernamePasswordAuthenticationToken.class));
    }

    /** The {@code ldapAuthenticationProvider} bean as the security XML builds it. */
    private static AuthenticationProvider wiredLdapProvider(String enabled) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(factory).loadBeanDefinitions(new ClassPathResource(
                "at/ac/meduniwien/ophthalmology/libreclinica/applicationContext-core-security.xml"));
        // Component-scanned in the application; a stand-in is enough here.
        factory.registerSingleton("openClinicaLdapUserSearch", mock(LdapUserSearch.class));
        // The real context source needs the java.naming export the Tomcat
        // runtime is started with (Dockerfile CATALINA_OPTS) but surefire is not.
        factory.removeBeanDefinition("contextSource");
        factory.registerSingleton("contextSource", mock(BaseLdapPathContextSource.class));

        Properties props = new Properties();
        if (enabled != null) {
            props.setProperty("ldap.enabled", enabled);
        }
        PropertySourcesPlaceholderConfigurer placeholders = new PropertySourcesPlaceholderConfigurer();
        placeholders.setProperties(props);
        placeholders.setIgnoreUnresolvablePlaceholders(true);
        placeholders.postProcessBeanFactory(factory);

        return factory.getBean("ldapAuthenticationProvider", AuthenticationProvider.class);
    }
}
