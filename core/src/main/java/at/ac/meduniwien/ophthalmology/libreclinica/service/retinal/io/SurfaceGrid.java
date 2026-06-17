/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io;

/**
 * DR-022 — typed view of a per-B-scan surface-trace CSV emitted by the
 * GPU sidecar (e.g. {@code 001-OPL-HFL.csv}, {@code 001-RPEL.csv}).
 *
 * <p>{@code yPerBscan[b][a]} is the surface Y at A-scan {@code a} on
 * B-scan {@code b}. Missing values surface as {@link Double#NaN}.
 */
public record SurfaceGrid(int nBscans, int nAscans, double[][] yPerBscan) { }
