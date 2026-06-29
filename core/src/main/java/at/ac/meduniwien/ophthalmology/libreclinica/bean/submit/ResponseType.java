/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.submit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Privilege;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Term;

/**
 * @author jxu
 *
 * Text  free form text entry limited to one line
 *
 * Text Area  free form text area display
 *
 * Single-Select - pick one from a list
 *
 * Multi-Select  pick many from a list
 *
 * Checkbox  selecting multiple options
 *
 * Radio button  selecting one from many options
 *
 * File  for upload of files
 * @deprecated
 */

// Internationalized description in Term.getDescription()
@SuppressWarnings("all")
@Deprecated
public class ResponseType extends Term {
    /**
	 * 
	 */
	private static final long serialVersionUID = 213278797848776832L;

	@Deprecated
	public static final ResponseType TEXT = new ResponseType(1, "text", null);

    @Deprecated
    public static final ResponseType TEXT_AREA = new ResponseType(2, "text area", null);

    @Deprecated
    public static final ResponseType SINGLE_SELECT = new ResponseType(3, "single-select", null);

    @Deprecated
    public static final ResponseType MULTI_SELECT = new ResponseType(4, "multi-select", null);

    @Deprecated
    public static final ResponseType CHECKBOX = new ResponseType(5, "checkbox", null);

    @Deprecated
    public static final ResponseType RADIOBUTTON = new ResponseType(6, "radio", null);

    @Deprecated
    public static final ResponseType FILE = new ResponseType(7, "file", null);

    private static final ResponseType[] members = { TEXT, TEXT_AREA, SINGLE_SELECT, MULTI_SELECT, CHECKBOX, RADIOBUTTON, FILE };

    @Deprecated
    public static final List<ResponseType> list = Arrays.asList(members);
    
    private ResponseType(int id, String name, Privilege[] myPrivs) {
        super(id, name);
    }

    private ResponseType() {
    }

    @Deprecated
    public static boolean contains(int id) {
        return Term.contains(id, list);
    }

    @Deprecated
    public static ResponseType get(int id) {
    	Optional<ResponseType> o = list.stream().filter(r -> r.getId() == id).findFirst();
    	return o.orElse(new ResponseType());
    }

    @Deprecated
    public static ResponseType getByName(String name) {
        for (int i = 0; i < list.size(); i++) {
            ResponseType temp = (ResponseType) list.get(i);
            if (temp.getName().equals(name)) {
                return temp;
            }
        }
        return new ResponseType();
    }

    @Deprecated
    public static boolean findByName(String name) {
        for (int i = 0; i < list.size(); i++) {
            ResponseType temp = (ResponseType) list.get(i);
            if (temp.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Deprecated
    public static ArrayList<ResponseType> toArrayList() {
        return new ArrayList<>(list);
    }

    @Override
    @Deprecated
    public String getName() {
        return name;
    }

}
