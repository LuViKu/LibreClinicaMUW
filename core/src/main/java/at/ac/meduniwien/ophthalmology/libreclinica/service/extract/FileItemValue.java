/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ItemDataType;

/**
 * What a FILE item looks like once it leaves the platform.
 *
 * <p>P3.7 — a FILE item's stored value is a path on the server, and every
 * export emitted it verbatim. To the recipient that is worthless: they have no
 * access to that filesystem, so the answer to "which file did they attach?" is
 * a string they cannot act on. To an attacker it is free reconnaissance — the
 * directory layout of a machine holding patient data, handed to whoever
 * receives a casebook or a dataset extract, neither of which is a privileged
 * artifact once downloaded.
 *
 * <p>The file itself now travels in the subject bundle; the text formats name
 * it. One implementation, because the leak had two routes — the per-subject
 * export and the dataset extract — and fixing one would have left the other.
 */
public final class FileItemValue {

    private FileItemValue() {}

    /**
     * The value to export, with a FILE item's path reduced to its filename.
     *
     * <p>Keyed on the declared data type rather than on the value looking
     * path-like: a free-text answer may legitimately contain a slash, and
     * truncating one would silently corrupt clinical data.
     *
     * @param rawValue        the stored {@code item_data.value}
     * @param itemDataTypeId  the item's {@code item_data_type_id}
     * @return the filename for a FILE item, the value unchanged otherwise
     */
    public static String forExport(String rawValue, int itemDataTypeId) {
        if (rawValue == null || rawValue.isEmpty()
                || itemDataTypeId != ItemDataType.FILE.getId()) {
            return rawValue;
        }
        int slash = Math.max(rawValue.lastIndexOf('/'), rawValue.lastIndexOf('\\'));
        return (slash >= 0 && slash < rawValue.length() - 1)
                ? rawValue.substring(slash + 1)
                : rawValue;
    }
}
