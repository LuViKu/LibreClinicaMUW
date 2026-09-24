/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 * For details see: https://libreclinica.org/license
 * LibreClinica, copyright (C) 2020-2026
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.RetinalJobFollower.Action;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.RetinalJobFollower.ExistingJob;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.RetinalJobFollower.Step;

/**
 * DR-035 — what a bind owes: the plan's tasks against the scan's existing jobs.
 */
class RetinalJobFollowerTest {

    @Test
    void aScanWithNoJobsGetsOneJobPerPlannedTask() {
        List<Step> steps = RetinalJobFollower.plan(List.of("fluid", "layers"), List.of());
        assertEquals(List.of(
                new Step(Action.ENQUEUE, "fluid", null),
                new Step(Action.ENQUEUE, "layers", null)), steps);
    }

    @Test
    void finishedJobsAreAttachedNotRecomputed() {
        List<Step> steps = RetinalJobFollower.plan(List.of("fluid"),
                List.of(new ExistingJob(7, "fluid", "done")));
        assertEquals(List.of(new Step(Action.ATTACH, "fluid", 7L)), steps);
    }

    @Test
    void aFinishedJobFollowsTheScanEvenWhenTheNewPlanDoesNotWantItsTask() {
        // The metrics exist; a configuration decides only what new work starts.
        List<Step> steps = RetinalJobFollower.plan(List.of("fluid"),
                List.of(new ExistingJob(7, "layers", "done")));
        assertEquals(List.of(
                new Step(Action.ATTACH, "layers", 7L),
                new Step(Action.ENQUEUE, "fluid", null)), steps);
    }

    @Test
    void aRunningJobIsAttachedAndNotDuplicated() {
        List<Step> steps = RetinalJobFollower.plan(List.of("fluid"),
                List.of(new ExistingJob(7, "fluid", "segmenting")));
        assertEquals(List.of(new Step(Action.ATTACH, "fluid", 7L)), steps);
    }

    @Test
    void aCancelledJobIsRevivedWhenThePlanWantsItsTaskAndStaysCancelledOtherwise() {
        assertEquals(List.of(new Step(Action.REVIVE, "fluid", 7L)),
                RetinalJobFollower.plan(List.of("fluid"), List.of(new ExistingJob(7, "fluid", "cancelled"))));
        assertTrue(RetinalJobFollower.plan(List.of(), List.of(new ExistingJob(7, "fluid", "cancelled"))).isEmpty());
    }

    @Test
    void anEmptyPlanStillAttachesWhatExists() {
        List<Step> steps = RetinalJobFollower.plan(List.of(),
                List.of(new ExistingJob(7, "fluid", "done"), new ExistingJob(8, "ga", "failed")));
        assertEquals(List.of(
                new Step(Action.ATTACH, "fluid", 7L),
                new Step(Action.ATTACH, "ga", 8L)), steps);
    }

    @Test
    void twoRowsForTheSameTaskCountOnce() {
        // Cannot happen under the unique key, but an older duplicate must not
        // produce two attaches or an enqueue on top of an attach.
        List<Step> steps = RetinalJobFollower.plan(List.of("fluid"),
                List.of(new ExistingJob(7, "fluid", "done"), new ExistingJob(9, "fluid", "cancelled")));
        assertEquals(List.of(new Step(Action.ATTACH, "fluid", 7L)), steps);
    }
}
