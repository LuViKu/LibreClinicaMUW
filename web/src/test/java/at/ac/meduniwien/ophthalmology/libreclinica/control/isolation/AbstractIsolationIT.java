/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.isolation;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.mock.web.MockHttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;

/**
 * Base of the cross-site isolation ITs of the heritage servlets: one container,
 * a shared read-only fixture (two sites, four site-A users) and a fresh pair of
 * sites for every request that writes, so that a leak cannot change what the
 * next test reads.
 */
abstract class AbstractIsolationIT extends AbstractApiControllerDatabaseIT {

    static IsolationFixture shared;
    static IsolationDriver driver;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @BeforeAll
    static void seedSharedFixture() throws SQLException {
        shared = IsolationFixture.seed(DATA_SOURCE, "");
        driver = new IsolationDriver(DATA_SOURCE);
    }

    /** A pair of sites nobody else has touched. */
    static IsolationFixture fresh() throws SQLException {
        return IsolationFixture.seed(DATA_SOURCE, String.valueOf(SEQUENCE.incrementAndGet()));
    }

    /** What one request did, seen from {@code target}'s data. */
    record Verdict(IsolationDriver.Outcome out, List<String> changedTables, List<String> readerSees) {

        boolean clean() {
            return changedTables.isEmpty() && readerSees.isEmpty();
        }

        String describe() {
            return "reader saw " + readerSees + ", rows changed in " + changedTables + "; " + out;
        }
    }

    /** Runs {@code probe} as site A's {@code role}, aimed at {@code target}'s ids. */
    static Verdict run(IsolationProbes.Probe probe, IsolationFixture fx, String role, IsolationFixture.Site target)
            throws SQLException {
        if (probe.precondition() != null) {
            IsolationFixture.update(DATA_SOURCE, probe.precondition()
                    .replace("%SS%", String.valueOf(target.studySubjectId))
                    .replace("%EV%", String.valueOf(target.eventId))
                    .replace("%EC%", String.valueOf(target.eventCrfId)));
        }
        Map<String, String> before = driver.fingerprint();
        MockHttpServletRequest req = driver.request(probe.method(), probe.path(), fx.a.userName(role),
                probe.params().apply(target));
        IsolationDriver.Outcome out = driver.run(probe.servlet().get(), req);
        Map<String, String> after = driver.fingerprint();
        return new Verdict(out, IsolationDriver.changed(before, after), out.readerSees(target.markers()));
    }

    /**
     * Whether the request got past the access check: it produced the site's own
     * data or changed its rows, or it was not turned away (no refusal page, no
     * InsufficientPermissionException) and ended on a page of its own.
     */
    static boolean gotPastTheAccessCheck(Verdict v) {
        IsolationDriver.Outcome o = v.out();
        boolean refusedByException = o.swallowed != null && o.swallowed.contains("InsufficientPermission");
        return !v.readerSees().isEmpty() || !v.changedTables().isEmpty()
                || (!o.refused() && !refusedByException && (o.firstForward() != null || !o.body.isEmpty()));
    }

    /** Every (probe, role) pair of the probes {@code selected} picks. */
    static Stream<Arguments> attacks(boolean leaking) {
        List<Arguments> args = new ArrayList<>();
        for (IsolationProbes.Probe p : IsolationProbes.all()) {
            if (IsolationProbes.LEAKS.contains(p.name()) != leaking) {
                continue;
            }
            for (String role : IsolationFixture.SITE_ROLES) {
                args.add(Arguments.of(p.name(), role));
            }
        }
        return args.stream();
    }

    static IsolationProbes.Probe probe(String name) {
        return IsolationProbes.all().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }
}
