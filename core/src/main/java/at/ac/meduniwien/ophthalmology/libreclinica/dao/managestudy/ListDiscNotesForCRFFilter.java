/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy;

import org.apache.commons.lang.StringEscapeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@SuppressWarnings("all")

public class ListDiscNotesForCRFFilter implements CriteriaCommand {

    List<Filter> filters = new ArrayList<Filter>();
    HashMap<String, String> columnMapping = new HashMap<String, String>();
    Integer studyEventDefinitionId;

    public ListDiscNotesForCRFFilter(Integer studyEventDefinitionId) {
        columnMapping.put("studySubject.label", "ss.label");
        columnMapping.put("studySubject.status", "ss.status_id");
        columnMapping.put("studySubject.oid", "ss.oc_oid");
        columnMapping.put("studySubject.secondaryLabel", "ss.secondary_label");
        columnMapping.put("subject.charGender", "s.gender");
        this.studyEventDefinitionId = studyEventDefinitionId;

    }

    public void addFilter(String property, Object value) {
        filters.add(new Filter(property, value));
    }

    @Override
    public String execute(String criteria) {
        String theCriteria = "";
        for (Filter filter : filters) {
            if (filter.getProperty().equals("dn.discrepancy_note_type_id") || filter.getProperty().equals("dn.resolution_status_id")) {
                theCriteria += buildCriteriaForSelect(criteria, filter.getProperty(), filter.getValue());
            }
        }
        //theCriteria += " ) ";
        for (Filter filter : filters) {
            if (!filter.getProperty().equals("dn.discrepancy_note_type_id") && !filter.getProperty().equals("dn.resolution_status_id")) {
                theCriteria += buildCriteria(criteria, filter.getProperty(), filter.getValue());
            }
        }

        return theCriteria;
    }

    private String buildCriteriaForSelect(String criteria, String property, Object value) {

        value = StringEscapeUtils.escapeSql(value.toString());
        if (value != null) {
            if (property.equals("dn.discrepancy_note_type_id")) {
                Integer typeId = asIntOrNull(value.toString());
                if (typeId != null && typeId > 0 && typeId < 10) {
                    criteria += " and " + property + " = " + value.toString() + " ";
                }
            } else if (property.equals("dn.resolution_status_id")) {
                criteria += value.toString();
            }
        }
        return criteria;
    }

    private String buildCriteria(String criteria, String property, Object value) {
        value = StringEscapeUtils.escapeSql(value.toString());
        if (value != null) {
            if (property.equals("status")) {
                criteria = criteria + " and ";
                criteria = criteria + " " + columnMapping.get(property) + " = " + value.toString() + " ";
            } else if (property.equals("event.status")) {
                if (!value.equals("2")) {
                    criteria += " and ";
                    criteria += " ( se.study_event_definition_id = " + studyEventDefinitionId;
                    criteria += " and se.subject_event_status_id = " + value + " )";
                } else {
                    criteria += " AND (se.study_subject_id is null or (se.study_event_definition_id != " + studyEventDefinitionId;
                    criteria += " AND (select count(*) from  study_subject ss1 LEFT JOIN study_event ON ss1.study_subject_id = study_event.study_subject_id";
                    criteria +=
                        " where  study_event.study_event_definition_id =" + studyEventDefinitionId + " and ss.study_subject_id = ss1.study_subject_id) =0))";

                }
            } else if (property.startsWith("crf_")) {
                Integer crfId = asIntOrNull(property.substring(4));
                Integer stageId = asIntOrNull(value.toString());
                boolean uncompleted = value.equals("1"); // DataEntryStage.UNCOMPLETED
                if (crfId == null || (!uncompleted && stageId == null)) {
                    // not a crf_<id> column, or not a data-entry stage id:
                    // there is no term to build from it
                    return criteria;
                }

                if (!uncompleted) { // crf data entry stages other than
                    // DataEntryStage.UNCOMPLETED
                    int stage = getStatusForStage(stageId);
                    criteria +=
                        " AND "
                            + stage
                            + " = ("
                            + "SELECT event_crf.status_id FROM event_crf event_crf, crf_version crf_version WHERE study_event_id in (SELECT se.study_event_id FROM study_event se, study_event_definition sed"
                            + " WHERE se.study_subject_id=SS.SUBJECT_ID" + " and se.study_event_definition_id = " + studyEventDefinitionId
                            + " and se.study_event_definition_id= sed.study_event_definition_id)" + " and crf_version.crf_id = " + crfId
                            + " and event_crf.crf_version_id = crf_version.crf_version_id";
                    if (value.equals("3")) { // DataEntryStage.INITIAL_DATA_ENTRY_COMPLETE
                        criteria += " and event_crf.validator_id = 0";
                    } else if (value.equals("4")) { // DataEntryStage.DOUBLE_DATA_ENTRY
                        criteria += " and event_crf.validator_id != 0";
                    }
                    criteria += ")";
//                    criteria += " order by event_crf_id asc" + ")";
                } else {// DataEntryStage.UNCOMPLETED
                    criteria += " AND (se.study_subject_id is null or (se.study_event_definition_id != " + studyEventDefinitionId;
                    criteria += " AND (select count(*) from  study_subject ss1 LEFT JOIN study_event ON ss1.study_subject_id = study_event.study_subject_id";
                    criteria +=
                        " where  study_event.study_event_definition_id =" + studyEventDefinitionId + " and ss.study_subject_id = ss1.study_subject_id) =0))";
                }

            } else {
                criteria = criteria + " and ";
                criteria = criteria + " UPPER(" + columnMapping.get(property) + ") like UPPER('%" + value.toString() + "%')" + " ";
            }
        }
        return criteria;
    }

    /*
     * Method to convert DataEntryStage id value into status value to be match
     * it with event_crf table rows.
     */
    private int getStatusForStage(int stage) {
        int status = 0;

        if (stage == 2) // DataEntryStage.INITIAL_DATA_ENTRY
        {
            status = 1;
        }

        if (stage == 3 || stage == 4) // DataEntryStage.INITIAL_DATA_ENTRY_COMPLETE
        // / DataEntryStage.DOUBLE_DATA_ENTRY
        {
            status = 4;
        }

        if (stage == 5) // DataEntryStage.DOUBLE_DATA_ENTRY_COMPLETE
        {
            status = 2;
        }

        if (stage == 6) // DataEntryStage.ADMINISTRATIVE_EDITING
        {
            status = 6;
        }

        if (stage == 7) // DataEntryStage.LOCKED
        {
            status = 7;
        }
        return status;
    }

    /**
     * The candidate as an int, or {@code null} when it is not one.
     *
     * <p>Both the filter property and the filter value reach this class
     * straight from the listing request, so a hand-crafted request can put a
     * non-number where a numeric column id or status id is expected. The term
     * is then dropped — the same thing this filter already does with a value
     * it does not recognise — instead of aborting the whole listing with an
     * uncaught NumberFormatException.
     */
    private static Integer asIntOrNull(String candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            return Integer.valueOf(candidate);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static class Filter {
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

}
