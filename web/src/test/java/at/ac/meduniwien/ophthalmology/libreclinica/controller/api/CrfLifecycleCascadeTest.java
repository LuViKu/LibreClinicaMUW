/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Where an event-definition CRF's default goes when its version stops taking
 * data: the newest available version its {@code selected_version_ids} allows.
 * The database side is covered by {@code CrfsApiControllerLifecycleDatabaseIT}.
 */
class CrfLifecycleCascadeTest {

    @Test
    void theDefaultMovesToTheNewestVersionTheSelectionAllows() {
        List<Integer> newestFirst = List.of(30, 20, 10);
        assertThat(CrfLifecycleCascade.firstAllowed(newestFirst, null)).isEqualTo(30);
        assertThat(CrfLifecycleCascade.firstAllowed(newestFirst, "")).isEqualTo(30);
        assertThat(CrfLifecycleCascade.firstAllowed(newestFirst, "10, 20")).isEqualTo(20);
    }

    @Test
    void withNoAllowedVersionTheDefaultStays() {
        assertThat(CrfLifecycleCascade.firstAllowed(List.of(30, 20, 10), "5")).isNull();
        assertThat(CrfLifecycleCascade.firstAllowed(List.of(), null)).isNull();
    }
}
