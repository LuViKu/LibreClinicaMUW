/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.enumsupport;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.Context;

/**
 * Concrete {@link CodedEnumType} pre-bound to the rule
 * {@link Context} enum. Phase B.5 — see {@link StatusType} for rationale.
 */
public class RuleContextType extends CodedEnumType {
    public RuleContextType() {
        setEnumClass(Context.class);
    }
}
