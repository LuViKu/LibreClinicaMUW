/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.contract;

import at.ac.meduniwien.ophthalmology.libreclinica.templates.HibernateOcDbTestCase;
import org.dbunit.dataset.DefaultDataSet;
import org.dbunit.dataset.IDataSet;

/**
 * Base for Phase C boot-contract characterisation tests. The C.0 suite
 * verifies bean-graph contracts, not row data, so an empty DBUnit fixture
 * is enough — the parent {@link HibernateOcDbTestCase}'s setUp wires the
 * full Spring context (which is the unit under test) but skips fixture
 * loading via the no-rows dataset.
 *
 * <p>Phase C playbook §C.0.
 */
public abstract class AbstractContractIT extends HibernateOcDbTestCase {

    @Override
    protected IDataSet getDataSet() {
        // Empty fixture — contract tests inspect the Spring context, not
        // DB rows. The Liquibase changesets that ran during test-DB
        // bootstrap are sufficient for the EntityManagerFactory to
        // initialise its metamodel.
        return new DefaultDataSet();
    }
}
