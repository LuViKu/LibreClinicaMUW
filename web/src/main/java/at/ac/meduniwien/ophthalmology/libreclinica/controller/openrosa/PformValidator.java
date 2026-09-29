/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.openrosa;

import at.ac.meduniwien.ophthalmology.libreclinica.logic.expressionTree.ExpressionTreeHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

@SuppressWarnings("all")

public class PformValidator implements Validator {
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());

    @Override
    public boolean supports(Class<?> clazz) {
        return ItemItemDataContainer.class.equals(clazz);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.springframework.validation.Validator#validate(java.lang.Object,
     * org.springframework.validation.Errors)
     */
    @Override
    public void validate(Object target, Errors e) {
        ItemItemDataContainer container = (ItemItemDataContainer) target;
        String origValue = container.getItemData().getValue();
        Integer responseTypeId = container.getResponseTypeId();
        Integer itemDataTypeId = container.getItem().getItemDataType().getItemDataTypeId();
        logger.info("*** Data type id:  ***" + itemDataTypeId);

        if (responseTypeId == 3 || responseTypeId == 7) {
            String[] values = origValue.split(",");
            for (String value : values) {
                subValidator(itemDataTypeId, value.trim(), e);
            }
        } else {
            subValidator(itemDataTypeId, origValue, e);

        }
    }

    public void subValidator(Integer itemDataTypeId, String value, Errors e) {
        if (value != null && value != "") {

            switch (itemDataTypeId) {
            case 5: { // ItemDataType.STRING
                    if (value.length()>3999){                   
                    e.reject("value.invalid.STRING");
                    logRejected("value.invalid.STRING (over 3999 characters)", itemDataTypeId, value);
                    }
                break;
            }
            case 6: { // ItemDataType.INTEGER
                try {
                    Integer.valueOf(value);
                } catch (NumberFormatException nfe) {
                    e.reject("value.invalid.Integer");
                    logRejected("value.invalid.INTEGER", itemDataTypeId, value);
                }
                break;
            }
            case 7: { // ItemDataType.REAL
                try {
                    Float.valueOf(value);
                } catch (NumberFormatException nfe) {
                    e.reject("value.invalid.float");
                    logRejected("value.invalid.REAL", itemDataTypeId, value);
                }
                break;
            }
            case 9: { // ItemDataType.DATE
                if (!ExpressionTreeHelper.isDateyyyyMMddDashes(value)) {
                    e.reject("value.invalid.date");
                    logRejected("value.invalid.DATE", itemDataTypeId, value);
                }
                break;
            }
            case 10: { // ItemDataType.PDATE
                if (!ExpressionTreeHelper.isDateyyyyMMddDashes(value) && !ExpressionTreeHelper.isDateyyyyMMDashes(value)
                        && !ExpressionTreeHelper.isDateyyyyDashes(value)) {
                    e.reject("value.invalid.pdate");
                    logRejected("value.invalid.PDATE", itemDataTypeId, value);
                }
                break;
            }
            case 11: { // ItemDataType.FILE
           //     e.reject("value.notSupported.file");
                break;
            }

            default:
                break;
            }

        }
    }

    /**
     * The rejected value is participant-entered CRF data, so only its data
     * type and length are logged.
     */
    private void logRejected(String reason, Integer itemDataTypeId, String value) {
        logger.info("Rejected OpenRosa item value: {} (item data type id {}, {} characters)",
                reason, itemDataTypeId, value.length());
    }

}
