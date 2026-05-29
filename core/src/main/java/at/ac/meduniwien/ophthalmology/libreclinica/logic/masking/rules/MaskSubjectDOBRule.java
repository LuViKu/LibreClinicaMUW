/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.logic.masking.rules;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.EntityBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.core.BusinessRule;

/**
 * @author thickerson
 * Created on Sep 1, 2005
 */
public class MaskSubjectDOBRule implements BusinessRule {
    public boolean isPropertyTrue(String s) {
        if (s.equals(this.getClass().getName())) {
            return true;
        } else {
            return false;
        }
    }

    public EntityBean doAction(EntityBean sb) {
        // cast to a subject bean
        SubjectBean ssb = (SubjectBean) sb;
        ssb.setDateOfBirth(null);// effectively xx-xx-xxxx
        return sb;
    }

}
