/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.enumsupport;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.LoginStatus;

/**
 * Concrete {@link CodedEnumType} pre-bound to the
 * {@link LoginStatus} enum. Phase B.5 — see {@link StatusType} for rationale.
 */
public class LoginStatusType extends CodedEnumType {
    public LoginStatusType() {
        setEnumClass(LoginStatus.class);
    }
}
