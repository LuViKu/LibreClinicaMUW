/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;

/**
 * Created by IntelliJ IDEA.
 * User: A. Hamid
 * Date: Apr 12, 2010
 * Time: 3:32:44 PM
 *
 * <p>Two jobs: {@code ecId} asks whether an event CRF is locked for data entry
 * by someone; {@code userId} (sent by the data-entry page when it is left)
 * releases the CRF locks of the <em>session</em> user and redirects to
 * {@code exitTo}. The {@code userId} value itself is ignored — a user can only
 * release their own locks — and {@code exitTo} is followed only when it is a
 * path inside this application.
 */
@SuppressWarnings("all")
public class CheckCRFLocked extends SecureController {
    /**
	 *
	 */
	private static final long serialVersionUID = 4520097023965266054L;

    /** Where the lock release lands when {@code exitTo} is missing or refused. */
    static final String DEFAULT_EXIT = "ListStudySubjects";

	@Override
    protected void processRequest() throws Exception {
        int userId;
        String ecId = request.getParameter("ecId");
        if (ecId != null && !ecId.equals("")) {
            int crfId = Integer.parseInt(ecId);
            if (getCrfLocker().isLocked(crfId)) {
                userId = getCrfLocker().getLockOwner(crfId);
                UserAccountDAO udao = new UserAccountDAO(sm.getDataSource());
                UserAccountBean ubean = (UserAccountBean)udao.findByPK(userId);
                response.getWriter().print(resword.getString("CRF_unavailable") +
                        "\n"+ubean.getName() + " "+ resword.getString("Currently_entering_data")
                        + "\n"+resword.getString("Leave_the_CRF"));
            } else {
                response.getWriter().print("true");
            }
            return;
        }else if(request.getParameter("userId")!=null) {
            if (ub != null && ub.getId() > 0) {
                getCrfLocker().unlockAllForUser(ub.getId());
            }
            String exitTo = safeExitTo(request.getParameter("exitTo"), request.getContextPath());
            response.sendRedirect(exitTo != null ? exitTo : DEFAULT_EXIT);
        }
    }

    /**
     * {@code exitTo} when it stays inside this application, otherwise null.
     *
     * <p>Accepted: a relative path ({@code ViewStudySubject?id=5}, resolved by
     * the browser against this servlet's directory) or an absolute path under
     * the context path ({@code /LibreClinica/ViewStudySubject?id=5}). Refused:
     * anything with a scheme ({@code https:}, {@code javascript:}), a
     * protocol-relative {@code //host}, backslashes (which browsers read as
     * slashes), control characters or whitespace, and absolute paths outside
     * the context.
     */
    static String safeExitTo(String exitTo, String contextPath) {
        if (exitTo == null || exitTo.isEmpty() || exitTo.length() > 2000) {
            return null;
        }
        for (int i = 0; i < exitTo.length(); i++) {
            char c = exitTo.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '\\') {
                return null;
            }
        }
        if (exitTo.startsWith("//")) {
            return null;
        }
        // A colon before the first '/', '?' or '#' makes the value a URL with a scheme.
        int end = exitTo.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = exitTo.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        if (exitTo.substring(0, end).indexOf(':') >= 0) {
            return null;
        }
        if (exitTo.startsWith("/")) {
            String prefix = (contextPath == null ? "" : contextPath) + "/";
            return exitTo.startsWith(prefix) ? exitTo : null;
        }
        return exitTo;
    }

    @Override
    protected void mayProceed() throws InsufficientPermissionException {
        return;
    }
}
