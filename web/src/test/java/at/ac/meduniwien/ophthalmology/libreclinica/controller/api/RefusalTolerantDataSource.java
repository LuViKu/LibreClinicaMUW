/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * The test database's data source: {@link SimpleDriverDataSource} opens a new TCP connection per call, through
 * the port Docker publishes for the Testcontainers PostgreSQL. Docker Desktop's port proxy now and then refuses
 * such a connection for a moment under a long run (the {@code Connection to host:port refused} seen in the
 * full web integration run). The call that met it never reached the database, so it is simply made again.
 *
 * <p>This has to sit below the application code: a refusal met inside a controller is turned into a
 * {@code 500} response by {@code ApiExceptionHandler}, so retrying the test body (as
 * {@code CrossSiteIsolationMatrix.retryingRefusedConnections} does for thrown failures) never sees it, and a
 * security gate would fail on a 500 that says nothing about isolation. Only a failure to <em>connect</em> is
 * retried; a statement is never repeated.
 */
final class RefusalTolerantDataSource extends SimpleDriverDataSource {

    private static final int ATTEMPTS = 6;

    @Override
    public Connection getConnection() throws SQLException {
        return retrying(super::getConnection);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return retrying(() -> super.getConnection(username, password));
    }

    private interface Opener {
        Connection open() throws SQLException;
    }

    private static Connection retrying(Opener opener) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                return opener.open();
            } catch (SQLException e) {
                if (attempt >= ATTEMPTS || !refused(e)) throw e;
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static boolean refused(Throwable failure) {
        for (Throwable c = failure; c != null; c = c.getCause()) {
            String m = String.valueOf(c.getMessage()).toLowerCase();
            if (m.contains("refused") || c instanceof java.net.ConnectException) return true;
        }
        return false;
    }
}
