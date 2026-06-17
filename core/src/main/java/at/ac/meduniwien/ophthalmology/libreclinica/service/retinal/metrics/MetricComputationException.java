/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics;

/**
 * DR-022 — thrown when a per-task metric computer cannot locate or read
 * one of its required artifacts (e.g. {@code fluidseg.npz},
 * {@code 001-OPL-HFL.csv}). The controller layer (Wave 3) catches this
 * and falls back gracefully (e.g. mark the job COMPLETE with a null
 * primaryValue rather than crashing the run).
 */
public class MetricComputationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MetricComputationException(String message) {
        super(message);
    }

    public MetricComputationException(String message, Throwable cause) {
        super(message, cause);
    }
}
