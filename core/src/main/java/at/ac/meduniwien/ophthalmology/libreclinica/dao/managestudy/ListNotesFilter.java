/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

@SuppressWarnings("all")

public class ListNotesFilter implements CriteriaCommand {

    List<Filter> filters = new ArrayList<Filter>();
    HashMap<String, String> columnMapping = new HashMap<String, String>();

    public ListNotesFilter() {
        columnMapping.put("studySubject.label", "ss.label");
        columnMapping.put("siteId", "ss.label");
        columnMapping.put("studySubject.labelExact", "ss.label");
        columnMapping.put("discrepancyNoteBean.createdDate", "dn.date_created");
        columnMapping.put("discrepancyNoteBean.updatedDate", "dn.date_created");
        columnMapping.put("discrepancyNoteBean.description", "dn.description");
        columnMapping.put("discrepancyNoteBean.user", "ua.user_name");
        columnMapping.put("discrepancyNoteBean.disType", "dn.discrepancy_note_type_id");
        columnMapping.put("discrepancyNoteBean.entityType", "dn.entity_type");
        columnMapping.put("discrepancyNoteBean.resolutionStatus", "dn.resolution_status_id");
        columnMapping.put("age", "age");
        columnMapping.put("days", "days");
    }

    public void addFilter(String property, Object value) {
        filters.add(new Filter(property, value));
    }

    public String execute(String criteria, HashMap<Integer, Object> variables) {
        String theCriteria = "";
        for (Filter filter : filters) {
            if (columnMapping.get(filter.getProperty()) == null) {
                continue;
            }
            theCriteria += buildCriteria(criteria, filter.getProperty(), filter.getValue(), variables);
        }
        return theCriteria;
    }

    @Override
    public String execute(String criteria) {
        return null;
    }

    /** The filter value as an int, or null when it is not one (a malformed filter is ignored). */
    private static Integer parseIntOrNull(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String buildCriteria(String criteria, String property, Object value, HashMap<Integer, Object> variables) {
        if (value != null) {
            if (property.equals("studySubject.labelExact")) {
                criteria = criteria + " and ";
                criteria = criteria + " UPPER(" + columnMapping.get(property) + ") = UPPER(?)" + " ";
                variables.put(variables.size() + 1, String.valueOf(value.toString()));
            } else if (property.equals("studySubject.label") || property.equals("discrepancyNoteBean.description")
                    || property.equals("discrepancyNoteBean.user")) {
                criteria = criteria + " and ";
                criteria = criteria + " UPPER(" + columnMapping.get(property) + ") like UPPER( ? )" + " ";
                variables.put(variables.size() + 1, '%' + value.toString() + '%');
            } else if (property.equals("siteId")) {
                criteria = criteria + " and ";
                criteria = criteria + "ss.study_id in ( SELECT study_id FROM study WHERE unique_identifier like  ? )";
                variables.put(variables.size() + 1, '%' + value.toString() + '%');
            } else if (property.equals("age") || property.equals("days")) {
                // "<5", ">10" or "=3" from the notes table's filter box. A
                // comparison whose number is not a number filters nothing, the
                // same as an empty box, rather than failing the whole listing.
                String filterValue = value.toString();
                Integer bound = filterValue.isEmpty() ? null : parseIntOrNull(filterValue.substring(1));
                if (bound != null && (filterValue.startsWith(">") || filterValue.startsWith("<") || filterValue.startsWith("="))) {
                    criteria = criteria + " and ";
                    criteria = criteria + " " + columnMapping.get(property) + " " + filterValue.substring(0, 1) + " ?";
                    variables.put(variables.size() + 1, bound);
                }
            } else if ("discrepancyNoteBean.disType".equalsIgnoreCase(property)) {
                if ("31".equals(value.toString())) {
                    criteria = criteria + " and ";
                    criteria = criteria + " (dn.discrepancy_note_type_id = 1 or dn.discrepancy_note_type_id = 3)";
                } else if (parseIntOrNull(value.toString()) != null) {
                    criteria = criteria + " and ";
                    criteria = criteria + " " + columnMapping.get(property) + " = ? ";
                    variables.put(variables.size() + 1, parseIntOrNull(value.toString()));
                }
            } else if ("discrepancyNoteBean.resolutionStatus".equalsIgnoreCase(property)) {
                if ("21".equals(value.toString())) {
                    criteria = criteria + " and ";
                    criteria = criteria + " (dn.resolution_status_id = 1 or dn.resolution_status_id = 2)";
                } else if (parseIntOrNull(value.toString()) != null) {
                    criteria = criteria + " and ";
                    criteria = criteria + " " + columnMapping.get(property) + " = ? ";
                    variables.put(variables.size() + 1, parseIntOrNull(value.toString()));
                }
            } else if ("discrepancyNoteBean.createdDate".equalsIgnoreCase(property) || "discrepancyNoteBean.updatedDate".equalsIgnoreCase(property)) {
                criteria = criteria + " and ";
                criteria = criteria + " " + columnMapping.get(property) + "::timestamp::date = ? ";
                String format = ResourceBundleProvider.getFormatBundle().getString("date_format_string");
                SimpleDateFormat sdf = new SimpleDateFormat(format);
                try {
                    variables.put(variables.size() + 1, sdf.parse(value.toString()));
                } catch (ParseException e) {
                    e.printStackTrace();
                }

            } else {
                criteria = criteria + " and ";
                criteria = criteria + " " + columnMapping.get(property) + " = ? ";
                variables.put(variables.size() + 1, String.valueOf(value.toString()));
            }
        }
        return criteria;
    }

    public static class Filter {
        private final String property;
        private final Object value;

        public Filter(String property, Object value) {
            this.property = property;
            this.value = value;
        }

        public String getProperty() {
            return property;
        }

        public Object getValue() {
            return value;
        }
    }

    public List<Filter> getFilters() {
        return filters;
    }

}
