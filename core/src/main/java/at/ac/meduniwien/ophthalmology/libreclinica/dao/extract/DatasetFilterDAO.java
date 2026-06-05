/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.extract;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.FilterBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

/**
 * Phase E.6 Data Export — composite read/write surface for the filter
 * predicates attached to a dataset.
 *
 * <p>The Phase 3 wire shape is a flat list of
 * {@code DatasetFilterDto} per dataset; each row maps to one
 * {@code filter} table entry (carrying the rendered SQL fragment)
 * plus one {@code dataset_filter_map} link row (carrying the
 * ordinal). This DAO is the thin orchestration layer over
 * {@link FilterDAO} + {@link DatasetFilterMapDAO} so the controller
 * doesn't reach across both for a single conceptual operation.
 *
 * <p>Not a subclass of {@code AuditableEntityDAO} because the
 * "dataset filter" isn't a row in its own table — it's the join of
 * one filter + one mapping row. Callers in the controller treat it
 * as a value-object operation; persistence is per-DTO.
 */
public class DatasetFilterDAO {

    private final DataSource ds;

    public DatasetFilterDAO(DataSource ds) {
        this.ds = ds;
    }

    /**
     * Persist a list of {@link PersistableFilter} predicates for a
     * dataset. Drops any prior {@code dataset_filter_map} rows for
     * the dataset (edits replace, not merge), creates one
     * {@code filter} row per DTO, then links them in submission
     * order.
     *
     * @return the list of created {@code filter.filter_id}s, in the
     *         same order as the input.
     */
    public List<Integer> replaceAll(int datasetId, UserAccountBean owner,
                                    List<PersistableFilter> filters) {
        DatasetFilterMapDAO mapDao = new DatasetFilterMapDAO(ds);
        FilterDAO filterDao = new FilterDAO(ds);

        mapDao.deleteLinksForDataset(datasetId);

        List<Integer> created = new ArrayList<>(filters.size());
        int ordinal = 0;
        for (PersistableFilter pf : filters) {
            FilterBean fb = new FilterBean();
            fb.setName(pf.name());
            fb.setDescription(pf.description());
            fb.setSQLStatement(pf.sqlFragment());
            fb.setStatus(Status.AVAILABLE);
            fb.setOwner(owner);
            fb.setOwnerId(owner.getId());

            FilterBean persisted = filterDao.create(fb);
            int filterId = persisted.getId();
            created.add(filterId);
            mapDao.insertLink(datasetId, filterId, ordinal);
            ordinal += 1;
        }
        return created;
    }

    /**
     * Lookup helper — returns every {@link FilterBean} attached to a
     * dataset, in authoring order. Useful when the SPA hydrates an
     * existing dataset for editing.
     */
    public List<FilterBean> findFiltersByDataset(int datasetId) {
        DatasetFilterMapDAO mapDao = new DatasetFilterMapDAO(ds);
        FilterDAO filterDao = new FilterDAO(ds);
        List<Integer> ids = mapDao.findFilterIdsByDataset(datasetId);
        List<FilterBean> out = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            FilterBean fb = (FilterBean) filterDao.findByPK(id);
            if (fb != null && fb.getId() > 0) {
                out.add(fb);
            }
        }
        return out;
    }

    /**
     * Insertion-shape for {@link #replaceAll}. The legacy
     * {@code FilterBean} carries a lot of auditing surface
     * irrelevant to the wire — this record only carries what
     * Phase 3 actually writes.
     */
    public record PersistableFilter(String name, String description, String sqlFragment) {}
}
