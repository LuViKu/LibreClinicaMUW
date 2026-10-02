/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import liquibase.Scope;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.parser.ChangeLogParserConfiguration;
import liquibase.parser.ChangeLogParserConfiguration.ChangelogParseMode;

/**
 * {@link SpringLiquibase} that parses the changelog in Liquibase's LAX mode.
 * <p>
 * Seven heritage changesets use {@code modifyColumn}, a Liquibase 1.9 change
 * type that later versions dropped: four in
 * {@code migration/2.5/changeLogCreateTables.xml}, three in
 * {@code migration/3.6/2015-05-21-OC-5994.xml}. Liquibase 3.6.3 skipped the
 * unknown element without a word, so those changesets have always run empty.
 * Every database records them as {@code EXECUTED} with the description
 * {@code empty} and the checksum of the empty string, and
 * {@code user_role.role_name} is still {@code VARCHAR(50)}, not the 64 the
 * element asks for. Liquibase 4 rejects an unknown change type in its default
 * STRICT mode. LAX skips it as 3.6.3 did, and the changesets cannot be edited
 * without breaking checksum validation.
 * <p>
 * LAX would also skip any unknown element added later, typo included.
 * {@code ChangelogElementsTest} fails the build if the changelog holds any
 * unknown change or precondition other than these seven.
 */
public class LaxParsingSpringLiquibase extends SpringLiquibase {

    @Override
    public void afterPropertiesSet() throws LiquibaseException {
        try {
            Scope.child(ChangeLogParserConfiguration.CHANGELOG_PARSE_MODE.getKey(), ChangelogParseMode.LAX,
                    super::afterPropertiesSet);
        } catch (LiquibaseException e) {
            throw e;
        } catch (Exception e) {
            throw new LiquibaseException(e);
        }
    }
}
