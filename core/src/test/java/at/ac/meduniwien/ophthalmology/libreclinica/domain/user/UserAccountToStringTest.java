package at.ac.meduniwien.ophthalmology.libreclinica.domain.user;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * toString() ends up in log lines, so it must not carry the authentication
 * secret, must name the user type rather than print the object identity, and
 * must not recurse into the parent account.
 */
public class UserAccountToStringTest {

    @Test
    public void toStringOmitsAuthSecretAndPrintsTypeNameAndParentId() {
        UserType type = new UserType();
        type.setUserType("user");
        UserAccount parent = new UserAccount();
        parent.setUserId(7);
        parent.setUserName("parent-login");
        UserAccount account = new UserAccount();
        account.setUserId(9);
        account.setUserName("alice");
        account.setUserType(type);
        account.setUserAccount(parent);
        account.setAuthsecret("TOPSECRET-VALUE");

        String text = account.toString();

        assertFalse(text, text.contains("TOPSECRET-VALUE"));
        assertFalse(text, text.contains("authSecret"));
        assertFalse("no default Object.toString() output expected: " + text, text.contains("@"));
        assertTrue(text, text.contains("userType=user"));
        assertTrue(text, text.contains("parentUserId=7"));
        assertFalse("parent account must not be printed in full: " + text, text.contains("parent-login"));
    }

    @Test
    public void toStringToleratesMissingTypeAndParent() {
        String text = new UserAccount().toString();
        assertTrue(text, text.contains("userType=null"));
        assertTrue(text, text.contains("parentUserId=null"));
    }
}
