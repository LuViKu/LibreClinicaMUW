/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpHeaders;

/**
 * DR-039 — what the preprocess sidecar says about the scan it normalised:
 * the format it came in, the device that recorded it, how its pixel spacing
 * was read, and which analyses are validated for that device.
 *
 * @param sourceFormat {@code e2e} or {@code dicom}
 * @param spacingOrder {@code standard}, {@code swapped} or {@code standard-assumed}
 * @param laterality   the eye the sidecar resolved (DICOM only), {@code OD} / {@code OS}
 * @param deviceTasks  the tasks whose models were trained on this device; empty
 *                     when none is, null when the sidecar did not say
 */
public record ScanSource(String sourceFormat, String manufacturer, String model,
                         String spacingOrder, String laterality, List<String> deviceTasks) {

    public static final String HEADER_SOURCE_FORMAT = "X-MUW-Source-Format";
    public static final String HEADER_MANUFACTURER = "X-MUW-Manufacturer";
    public static final String HEADER_MODEL = "X-MUW-Manufacturer-Model";
    public static final String HEADER_DEVICE_TASKS = "X-MUW-Device-Tasks";
    public static final String HEADER_LATERALITY = "X-MUW-Laterality";
    public static final String HEADER_SPACING_ORDER = "X-MUW-Spacing-Order";

    public static final String FORMAT_E2E = "e2e";
    public static final String FORMAT_DICOM = "dicom";

    /**
     * The device every task's model is trained on today
     * ({@code retinal_inference/devices.py} TASK_DEVICES). Used only to word
     * a refusal; which tasks run is decided by {@link #deviceTasks}.
     */
    public static final String VALIDATED_DEVICE_LABEL = "Heidelberg Spectralis";

    /** Null when the sidecar predates these headers (it sends no source format). */
    public static ScanSource from(HttpHeaders h) {
        if (h == null) return null;
        String format = clean(h.getFirst(HEADER_SOURCE_FORMAT));
        if (format == null) return null;
        String tasksRaw = h.getFirst(HEADER_DEVICE_TASKS);
        List<String> tasks = null;
        if (tasksRaw != null) {
            tasks = new ArrayList<>();
            for (String t : tasksRaw.split(",")) {
                String n = t.trim().toLowerCase(Locale.ROOT);
                if (!n.isEmpty()) tasks.add(n);
            }
        }
        String lat = clean(h.getFirst(HEADER_LATERALITY));
        return new ScanSource(format.toLowerCase(Locale.ROOT),
                clean(h.getFirst(HEADER_MANUFACTURER)),
                clean(h.getFirst(HEADER_MODEL)),
                clean(h.getFirst(HEADER_SPACING_ORDER)),
                lat == null ? null : lat.toUpperCase(Locale.ROOT),
                tasks == null ? null : List.copyOf(tasks));
    }

    public boolean isDicom() {
        return FORMAT_DICOM.equals(sourceFormat);
    }

    /** True only when the sidecar named this task as validated for the device. */
    public boolean validatedFor(String task) {
        return deviceTasks != null && task != null && deviceTasks.contains(task.toLowerCase(Locale.ROOT));
    }

    /** "Manufacturer Model", or "an unknown device". */
    public String deviceText() {
        String m = manufacturer == null ? "" : manufacturer;
        String mod = model == null ? "" : model;
        String both = (m + " " + mod).trim();
        return both.isEmpty() ? "an unknown device" : both;
    }

    /**
     * What the operator reads when {@code task} is not validated for this
     * scan's device: which device it is validated for, and which device the
     * scan is from.
     */
    public String unsupportedDeviceMessage(String task) {
        StringBuilder sb = new StringBuilder();
        sb.append(task).append(" is validated for ").append(VALIDATED_DEVICE_LABEL)
          .append(" only; this scan is from ").append(deviceText()).append('.');
        if (deviceTasks != null && !deviceTasks.isEmpty()) {
            sb.append(" Validated for this device: ").append(String.join(", ", deviceTasks)).append('.');
        }
        sb.append(" The scan was not sent for analysis.");
        return sb.toString();
    }

    private static String clean(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
