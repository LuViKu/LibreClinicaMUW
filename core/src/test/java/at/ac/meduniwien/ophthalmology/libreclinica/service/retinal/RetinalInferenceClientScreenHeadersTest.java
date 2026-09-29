/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The sidecar's /screen checks X-MUW-Inference-Token whenever it has a token
 * configured (production), so the synchronous screen must send it.
 */
public class RetinalInferenceClientScreenHeadersTest {

    @Test
    public void theTokenTravelsWhenOneIsConfigured() {
        HttpHeaders h = RetinalInferenceClient.screenHeaders(" per-host-secret ");
        assertEquals("per-host-secret", h.getFirst("X-MUW-Inference-Token"));
        assertEquals(MediaType.APPLICATION_JSON, h.getContentType());
    }

    @Test
    public void noHeaderWithoutAToken() {
        assertFalse(RetinalInferenceClient.screenHeaders(null).containsKey("X-MUW-Inference-Token"));
        assertFalse(RetinalInferenceClient.screenHeaders("  ").containsKey("X-MUW-Inference-Token"));
    }
}
