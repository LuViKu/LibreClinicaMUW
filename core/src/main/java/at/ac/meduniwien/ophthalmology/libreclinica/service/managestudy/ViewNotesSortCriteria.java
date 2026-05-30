/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.managestudy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import at.ac.meduniwien.ophthalmology.libreclinica.core.util.Pair;

/**
 * @author Doug Rodrigues (douglas.rodrigues@openclinica.com)
 *
 */
public class ViewNotesSortCriteria {

    private static final Map<String, String> SORT_BY_TABLE_COLUMN = new HashMap<String, String>();

    static {
        SORT_BY_TABLE_COLUMN.put("studySubject.label", "label");
        SORT_BY_TABLE_COLUMN.put("discrepancyNoteBean.createdDate", "date_created");
        SORT_BY_TABLE_COLUMN.put("days", "days");
        SORT_BY_TABLE_COLUMN.put("age", "age");
    }

    private final Map<String, String> sorters = new HashMap<String, String>();

    public static ViewNotesSortCriteria buildFilterCriteria(List<Pair<String,String>> sorts) {
        ViewNotesSortCriteria criteria = new ViewNotesSortCriteria();
        for (Pair<String,String> p: sorts) {
        	String
        		sortField = SORT_BY_TABLE_COLUMN.get(p.getFirst()),
        		sortOrder = p.getSecond();
        	if (sortField != null) {
        		criteria.getSorters().put(sortField, sortOrder);
        	}
        }
        return criteria;
    }
    
    // Phase B.4 jmesa PR 9 (cohort 7): SortSet-based buildFilterCriteria
    // overload removed — no jmesa-driven caller remains.

    public Map<String, String> getSorters() {
        return sorters;
    }

}
