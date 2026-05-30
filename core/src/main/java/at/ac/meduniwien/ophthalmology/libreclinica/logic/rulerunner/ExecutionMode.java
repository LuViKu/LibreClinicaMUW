/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.enumsupport.CodedEnum;

import java.util.HashMap;

public enum ExecutionMode implements CodedEnum {

    DRY_RUN(1, "Dry Run"), SAVE(2, "Save");

    private int code;
    private String description;

    ExecutionMode(int code) {
        this(code, null);
    }

    ExecutionMode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static Status getByName(String name) {
        return Status.valueOf(Status.class, name);
    }

    public static ExecutionMode getByCode(Integer code) {
        HashMap<Integer, ExecutionMode> enumObjects = new HashMap<Integer, ExecutionMode>();
        for (ExecutionMode theEnum : ExecutionMode.values()) {
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
