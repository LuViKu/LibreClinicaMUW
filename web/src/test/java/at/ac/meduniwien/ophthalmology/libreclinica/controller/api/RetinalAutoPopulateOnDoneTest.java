/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RemoteRetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalInferenceClient;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalJobStatusBroadcaster;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalResultItemDataPopulator;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics.RetinalMetricComputer;

/**
 * P2-4 — an inference result reaches the CRF without anyone asking.
 *
 * <p>The endpoint that copies a finished job's metrics into the visit's CRF has
 * existed since the nAMD work and had no caller: the numbers lived in a viewer
 * and the CRF stayed empty, so they never reached an export. The job's
 * completion now triggers it.
 *
 * <p>These pin the guard rails around that call. It runs after the result row
 * is persisted and the job is marked done, so nothing it does may undo that.
 */
class RetinalAutoPopulateOnDoneTest extends AbstractApiControllerTest {

    private RetinalInferenceApiController controllerWith(RetinalResultItemDataPopulator populator) {
        RetinalInferenceApiController c = new RetinalInferenceApiController(
                mockDataSource(),
                mock(SiteVisibilityFilter.class),
                mock(RetinalInferenceClient.class),
                mock(RemoteRetinalInferenceClient.class),
                mock(RetinalArtifactStorageService.class),
                mock(RetinalMetricComputer.class),
                mock(RetinalJobStatusBroadcaster.class));
        c.setRetinalAutoPopulator(populator);
        return c;
    }

    @Test
    void aFinishedJobPopulatesItsVisitsCrf() {
        RetinalResultItemDataPopulator populator = mock(RetinalResultItemDataPopulator.class);
        controllerWith(populator).autoPopulateCrf(42, 7);
        verify(populator).populateForEventCrf(eq(42), eq(7));
    }

    /**
     * A parked scan has no form to write into. Calling the populator with a
     * null CRF would be a lookup that can only fail.
     */
    @Test
    void aJobWithNoBoundCrfWritesNothing() {
        RetinalResultItemDataPopulator populator = mock(RetinalResultItemDataPopulator.class);
        controllerWith(populator).autoPopulateCrf(null, 7);
        verify(populator, never()).populateForEventCrf(anyInt(), anyInt());
    }

    /**
     * Without an account to attribute the write to, nothing is written. A CRF
     * value with no author is worse than a missing one.
     */
    @Test
    void noActorMeansNoWrite() {
        RetinalResultItemDataPopulator populator = mock(RetinalResultItemDataPopulator.class);
        controllerWith(populator).autoPopulateCrf(42, 0);
        verify(populator, never()).populateForEventCrf(anyInt(), anyInt());
    }

    /**
     * The job is already done and its artifacts are persisted when this runs.
     * A populate failure must not propagate and undo that — the operator can
     * still trigger the populate by hand.
     */
    @Test
    void aPopulateFailureDoesNotEscape() {
        RetinalResultItemDataPopulator populator = mock(RetinalResultItemDataPopulator.class);
        when(populator.populateForEventCrf(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));
        controllerWith(populator).autoPopulateCrf(42, 7);
        // Reaching here without an exception is the assertion.
        Mockito.verify(populator).populateForEventCrf(42, 7);
    }

    /** A deployment without the bean keeps the previous behaviour rather than failing. */
    @Test
    void anUnwiredPopulatorIsNotAnError() {
        RetinalInferenceApiController c = new RetinalInferenceApiController(
                mockDataSource(),
                mock(SiteVisibilityFilter.class),
                mock(RetinalInferenceClient.class),
                mock(RemoteRetinalInferenceClient.class),
                mock(RetinalArtifactStorageService.class),
                mock(RetinalMetricComputer.class),
                mock(RetinalJobStatusBroadcaster.class));
        c.autoPopulateCrf(42, 7);
    }
}
