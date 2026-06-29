/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.exception;

/**
 * @author thickerson
 *
 */
@SuppressWarnings("all")
public class PreparedStatementFactoryException extends Exception {
    /**
	 * 
	 */
	private static final long serialVersionUID = -8530147809391419915L;
	public String message;

    public PreparedStatementFactoryException() {
        message = "";
    }

    public PreparedStatementFactoryException(String message) {
        this.message = message;
    }

}
