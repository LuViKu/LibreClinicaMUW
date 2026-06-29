/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.otp;

/**
 * Enumeration of login results.
 * 
 * @author thillger
 */
@SuppressWarnings("all")
public enum LoginResult {
    SUCCESSFUL_LOGIN {

        @Override
        public String textual() {
            return "successful";
        }
    },
    DENIED_LOGIN {

        @Override
        public String textual() {
            return "unsuccessful";
        }
    };

    public abstract String textual();
}
