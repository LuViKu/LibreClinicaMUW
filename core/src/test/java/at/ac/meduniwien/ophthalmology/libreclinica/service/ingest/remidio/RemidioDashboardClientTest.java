/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClient.RemidioException;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClientTest.Call;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.remidio.RemidioGatewayClientTest.ScriptedTransport;

/**
 * The dashboard client against a scripted wire — the bodies and headers as
 * the web dashboard sends them (recorded 2026-09-23) and the answers the
 * backend gave a script the same day.
 */
public class RemidioDashboardClientTest {

    private static final RemidioDashboardClient.Settings SETTINGS = new RemidioDashboardClient.Settings(
            "https://remidio.example", "", "dash-token", "bot@example.org", "pw", 5898310359449600L);

    private static String ok(String data) {
        return "{\"status\":{\"statusCode\":\"OK\",\"message\":\"HTTP - Status OK\"},\"data\":" + data + ",\"paging\":null}";
    }

    private static ScriptedTransport loggedIn() {
        return new ScriptedTransport().answer(200, ok("\"bearer-1\""));
    }

    @Test
    public void lookupCarriesTheDashboardIdentityAndTreats404AsAbsent() throws Exception {
        ScriptedTransport wire = loggedIn().answer(404,
                "{\"status\":{\"statusCode\":\"NOT_FOUND\",\"message\":\"The Patient MRN you're looking for does not exist\"},"
                        + "\"data\":\"ResourceNotFoundException\",\"paging\":null}");
        RemidioDashboardClient client = new RemidioDashboardClient(SETTINGS, wire);

        Optional<Long> id = client.findPatientId("HAE-002");

        assertFalse(id.isPresent());
        Call login = wire.calls.get(0);
        assertEquals("/api/user/loginUser", login.path());
        assertEquals("WEB_DASHBOARD", login.headers().get("clientName"));
        assertEquals("dash-token", login.headers().get("clientIdentificationToken"));
        Call lookup = wire.calls.get(1);
        assertEquals("GET", lookup.method());
        assertEquals("/api/patient/getPatientWithExams/HAE-002?siteId=5898310359449600&deviceType=FOP", lookup.path());
        assertEquals("Bearer bearer-1", lookup.headers().get("Authorization"));
        assertEquals("rem", lookup.headers().get("tokentype"));
        assertFalse(lookup.headers().containsKey("clientAuthToken"));
    }

    @Test
    public void lookupReturnsThePatientIdWhenTheMrnExists() throws Exception {
        ScriptedTransport wire = loggedIn().answer(200, ok(
                "{\"patient\":{\"id\":6384834205188096,\"firstName\":\"TEST5\",\"lastName\":\"STUDY\","
                        + "\"dateOfBirth\":0,\"gender\":\"MALE\",\"mrn\":\"TEST5\",\"siteId\":5898310359449600},\"exams\":[]}"));
        RemidioDashboardClient client = new RemidioDashboardClient(SETTINGS, wire);

        assertEquals(Optional.of(6384834205188096L), client.findPatientId("TEST5"));
    }

    @Test
    public void createPatientSendsTheDashboardsBodyAndReturnsTheId() throws Exception {
        ScriptedTransport wire = loggedIn().answer(200, ok("6384834205188096"));
        RemidioDashboardClient client = new RemidioDashboardClient(SETTINGS, wire);

        long id = client.createPatient("HAE-002", "HAE-002", "STUDY", 0L, "FEMALE");

        assertEquals(6384834205188096L, id);
        Call create = wire.calls.get(1);
        assertEquals("POST", create.method());
        assertEquals("/api/patient/createPatient", create.path());
        String body = create.body();
        assertTrue(body.contains("\"mrn\":\"HAE-002\""));
        assertTrue("the dashboard sends an empty checksum", body.contains("\"checksum\":\"\""));
        assertTrue(body.contains("\"dateOfBirth\":0"));
        assertTrue(body.contains("\"gender\":\"FEMALE\""));
        assertTrue(body.contains("\"siteId\":5898310359449600"));
        assertTrue(body.contains("\"phoneNo\":\"\""));
    }

    @Test
    public void createExamNamesTheVisitAndReturnsTheExamId() throws Exception {
        ScriptedTransport wire = loggedIn().answer(200, ok(
                "{\"patientDetails\":{\"id\":6384834205188096},\"examDetails\":{\"id\":5480248510513152,"
                        + "\"localId\":\"LIBRECLINICA::42\",\"examCustomId\":\"LC42\",\"examDate\":1790187194225,"
                        + "\"deviceType\":[\"FOP\"],\"examState\":\"ACTIVE\"}}"));
        RemidioDashboardClient client = new RemidioDashboardClient(SETTINGS, wire);

        long id = client.createExam(6384834205188096L, "LC42", 1790187194225L, "LIBRECLINICA::42");

        assertEquals(5480248510513152L, id);
        String body = wire.calls.get(1).body();
        assertEquals("/api/exam/createExam", wire.calls.get(1).path());
        assertTrue(body.contains("\"examLocalId\":\"LIBRECLINICA::42\""));
        assertTrue(body.contains("\"examDate\":1790187194225"));
        assertTrue(body.contains("\"deviceType\":[\"FOP\"]"));
        assertTrue(body.contains("\"patientId\":6384834205188096"));
        assertTrue(body.contains("\"examCustomId\":\"LC42\""));
        assertTrue(body.contains("\"orderingProvider\":{"));
    }

    @Test
    public void a401ReLogsInOnceThenGivesUp() {
        ScriptedTransport wire = loggedIn()
                .answer(401, "{\"status\":{\"statusCode\":\"NOT_AUTHORIZED\"},\"data\":null}")
                .answer(200, ok("\"bearer-2\""))
                .answer(401, "{\"status\":{\"statusCode\":\"NOT_AUTHORIZED\"},\"data\":null}");
        RemidioDashboardClient client = new RemidioDashboardClient(SETTINGS, wire);

        RemidioException e = assertThrows(RemidioException.class, () -> client.findPatientId("X"));

        assertEquals(RemidioException.Reason.UNAUTHORIZED, e.reason());
        assertEquals(4, wire.calls.size());
        assertEquals("Bearer bearer-2", wire.calls.get(3).headers().get("Authorization"));
    }

    @Test
    public void unconfiguredNamesTheMissingKeyAndNeverCalls() {
        ScriptedTransport wire = new ScriptedTransport();
        RemidioDashboardClient client = new RemidioDashboardClient(
                new RemidioDashboardClient.Settings("https://remidio.example", "", "t", "e", "p", 0), wire);

        RemidioException e = assertThrows(RemidioException.class, () -> client.findPatientId("X"));

        assertEquals(RemidioException.Reason.UNCONFIGURED, e.reason());
        assertTrue(e.getMessage().contains(RemidioDashboardClient.KEY_SITE_ID));
        assertTrue(wire.calls.isEmpty());
    }

    @Test
    public void mrnsAreUrlEncodedInThePath() throws Exception {
        ScriptedTransport wire = loggedIn().answer(404, "{\"status\":{\"statusCode\":\"NOT_FOUND\"},\"data\":null}");
        RemidioDashboardClient client = new RemidioDashboardClient(SETTINGS, wire);

        client.findPatientId("A B/C");

        assertTrue(wire.calls.get(1).path().startsWith("/api/patient/getPatientWithExams/A%20B%2FC?"));
    }
}
