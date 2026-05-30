/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.enumsupport;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;

/**
 * Concrete {@link CodedEnumType} pre-bound to the {@link Status} enum.
 *
 * <p>Phase B.5 replaces the legacy {@code @Type(type = "status")}
 * annotation form (which relied on Hibernate 5's {@code <typedef name="status">}
 * mapping in {@code typedefs.hbm.xml}) with class-based
 * {@code @Type(StatusType.class)}.
 */
public class StatusType extends CodedEnumType {
    public StatusType() {
        setEnumClass(Status.class);
    }
}
