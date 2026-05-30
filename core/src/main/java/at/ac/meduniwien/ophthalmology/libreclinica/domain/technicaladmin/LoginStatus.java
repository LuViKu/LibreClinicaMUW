/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.enumsupport.CodedEnum;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import java.util.HashMap;
import java.util.ResourceBundle;

/*
 * Use this enum as login status holder
 * @author Krikor Krumlian
 *
 */

public enum LoginStatus implements CodedEnum {

    SUCCESSFUL_LOGIN(1, "successful_login"),
    FAILED_LOGIN(2, "failed_login"),
    FAILED_LOGIN_LOCKED(3, "failed_login_locked"),
    SUCCESSFUL_LOGOUT(4, "successful_logout"),
    ACCESS_CODE_VIEWED(5, "access_code_viewed"),
    // Phase D.5 (DR-014): SSO pre-auth audit codes. SSO_LOGIN fires
    // when the RequestHeaderAuthenticationFilter +
    // PreAuthenticatedAuthenticationProvider successfully resolves
    // the principal to a LibreClinica user. SSO_LOGIN_FAILED fires
    // when the principal header is present (from a trusted upstream)
    // but the UserProvisioningStrategy rejects (LOOKUP_ONLY miss, JIT
    // policy violation, etc.).
    SSO_LOGIN(6, "sso_login"),
    SSO_LOGIN_FAILED(7, "sso_login_failed");

    private int code;
    private String description;

    LoginStatus(int code) {
        this(code, null);
    }

    LoginStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    @Override
    public String toString() {
        ResourceBundle resterm = ResourceBundleProvider.getTermsBundle();
        return resterm.getString(getDescription());
    }

    public static LoginStatus getByName(String name) {
        return LoginStatus.valueOf(LoginStatus.class, name);
    }

    public static LoginStatus getByCode(Integer code) {
        HashMap<Integer, LoginStatus> enumObjects = new HashMap<Integer, LoginStatus>();
        for (LoginStatus theEnum : LoginStatus.values()) {
            enumObjects.put(theEnum.getCode(), theEnum);
        }
        return enumObjects.get(Integer.valueOf(code));
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

}
