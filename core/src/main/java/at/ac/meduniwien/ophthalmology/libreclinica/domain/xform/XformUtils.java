/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.xform;

import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.xform.dto.Html;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.xform.dto.Text;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.xform.dto.Translation;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.xform.dto.Value;

public class XformUtils {

    public static String getDefaultTranslation(Html html, String ref) {
        Translation translation = null;
        List<Translation> translations = html.getHead().getModel().getItext().getTranslation();

        // Get default translation
        for (Translation trans : translations) {
            if (trans.getDefaultLang() != null && trans.getDefaultLang().equals("true()"))
                translation = trans;
        }
        if (translation == null)
            translation = translations.get(0);

        List<Text> texts = translation.getText();

        // Lookup text translation
        for (Text text : texts) {
            if (text.getId().equals(ref)) {
                List<Value> values = text.getValue();
                for (Value value : values) {
                    if (value.getForm() == null && value.getValue() != null && !value.getValue().equals(""))
                        return value.getValue();
                }
            }
        }
        return "";
    }
}
