/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.enumsupport;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.ActionType;

/**
 * Concrete {@link CodedEnumType} pre-bound to the rule
 * {@link ActionType} enum. Phase B.5 — see {@link StatusType} for rationale.
 */
public class ActionTypeType extends CodedEnumType {
    public ActionTypeType() {
        setEnumClass(ActionType.class);
    }
}
