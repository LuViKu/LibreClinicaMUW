/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.enumsupport.CodedEnum;

import java.util.HashMap;

public enum DataEntryPhase implements CodedEnum {

    ADMIN_EDITING(1, "Administrative Editing"), INITIAL_DATA_ENTRY(2, "Initial Data Entry"), DOUBLE_DATA_ENTRY(3, "Double Data Entry"), IMPORT(4, "Import");

    private int code;
    private String description;

    DataEntryPhase(int code) {
        this(code, null);
    }

    DataEntryPhase(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static Status getByName(String name) {
        return Status.valueOf(Status.class, name);
    }

    public static DataEntryPhase getByCode(Integer code) {
        HashMap<Integer, DataEntryPhase> enumObjects = new HashMap<Integer, DataEntryPhase>();
        for (DataEntryPhase theEnum : DataEntryPhase.values()) {
            enumObjects.put(theEnum.getCode(), theEnum);
        }
        return enumObjects.get(Integer.valueOf(code));
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

}
