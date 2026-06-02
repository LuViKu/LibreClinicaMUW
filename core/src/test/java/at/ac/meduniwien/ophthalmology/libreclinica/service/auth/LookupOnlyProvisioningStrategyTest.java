/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.auth;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import java.util.Collections;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Phase D.4 (DR-014): unit tests for
 * {@link LookupOnlyProvisioningStrategy}. JIT scaffold is not
 * tested here — its current behaviour is identical (reject) and the
 * actual JIT impl is deferred per its class Javadoc.
 */
@RunWith(MockitoJUnitRunner.class)
public class LookupOnlyProvisioningStrategyTest {

    private static final String PROVIDER = "shibboleth-meduniwien";
    private static final String PRINCIPAL = "alice@meduniwien.ac.at";

    @Mock
    private UserAccountDAO userAccountDao;

    private LookupOnlyProvisioningStrategy strategy;

    @Before
    public void setUp() {
        strategy = new LookupOnlyProvisioningStrategy(userAccountDao);
    }

    /**
     * Happy path: a pre-provisioned user matching the
     * (provider, principal) pair is returned as-is.
     */
    @Test
    public void resolvesPreProvisionedUser() {
        UserAccountBean expected = new UserAccountBean();
        expected.setId(42);
        expected.setName("alice");
        expected.setExternalId(PRINCIPAL);
        expected.setExternalIdProvider(PROVIDER);
        when(userAccountDao.findByExternalIdentity(PROVIDER, PRINCIPAL))
                .thenReturn(expected);

        UserAccountBean actual = strategy.resolveOrProvision(
                PROVIDER, PRINCIPAL, Collections.<String, String>emptyMap());

        assertSame(expected, actual);
    }

    /**
     * Unknown principal — DAO returns the sentinel bean with id=0
     * ("no such user"). LOOKUP_ONLY must throw rather than auto-
     * create.
     */
    @Test(expected = UsernameNotFoundException.class)
    public void rejectsUnknownPrincipal() {
        UserAccountBean sentinel = new UserAccountBean();
        sentinel.setId(0);
        when(userAccountDao.findByExternalIdentity(PROVIDER, PRINCIPAL))
                .thenReturn(sentinel);

        strategy.resolveOrProvision(
                PROVIDER, PRINCIPAL, Collections.<String, String>emptyMap());
    }

    /**
     * DAO returns null (defensive — shouldn't happen per the DAO's
     * contract of returning a fresh sentinel bean, but pin the
     * strategy's behaviour anyway).
     */
    @Test
    public void rejectsNullFromDao() {
        when(userAccountDao.findByExternalIdentity(PROVIDER, PRINCIPAL))
                .thenReturn(null);

        try {
            strategy.resolveOrProvision(
                    PROVIDER, PRINCIPAL, Collections.<String, String>emptyMap());
            fail("Expected UsernameNotFoundException for null DAO result");
        } catch (UsernameNotFoundException expected) {
            // expected — message mentions the principal
            org.junit.Assert.assertTrue(
                    expected.getMessage().contains(PRINCIPAL));
        }
    }
}
