/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.extract;

/**
 * @author jxu
 *
 */
public class CommaReportBean extends TextReportBean {
    public CommaReportBean() {
        end = "\n";// ending character
        sep = ",";// seperating character
    }
}
