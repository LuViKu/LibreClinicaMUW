/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.extract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.FilterBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.FilterDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.FilterRow;

/**
 * <P>
 * Meant to serve as the first two steps for creating a filter, namely a)
 * showing a list of filters for users going directly to the list existing
 * filters function, and b) showing the instruction page for users who want to
 * start creating thier own filters.
 *
 * @author thickerson
 *
 *
 */
public class CreateFiltersOneServlet extends SecureController {

    /**
	 * 
	 */
	private static final long serialVersionUID = -4896891632087112903L;
	Locale locale;

    // < ResourceBundlerestext,resword,respage,resexception;

    @Override
    public void processRequest() throws Exception {
        // clean up the previous setup, if necessary
        session.removeAttribute("newExp");
        // removes the new explanation for setting up the create dataset
        // covers the plan if you cancel out of a process then want to get in
        // again, tbh
        String action = request.getParameter("action");
        if (action == null || action.trim().isEmpty()) {
            // our start page:
            // note that this is now set up to accept the
            // tabling classes created in View.
            FormProcessor fp = new FormProcessor(request);
            FilterDAO fdao = new FilterDAO(sm.getDataSource());
            EntityBeanTable table = fp.getEntityBeanTable();

            ArrayList<FilterBean> filters = new ArrayList<>();
            if (ub.isSysAdmin()) {
                filters = fdao.findAllAdmin();
            } else {
                filters = fdao.findAll();
            }
            ArrayList<FilterRow> filterRows = FilterRow.generateRowsFromBeans(filters);

            String[] columns =
                { resword.getString("filter_name"), resword.getString("description"), resword.getString("created_by"), resword.getString("created_date"),
                    resword.getString("status"), resword.getString("actions") };

            table.setColumns(new ArrayList<String>(Arrays.asList(columns)));
            table.hideColumnLink(5);
            table.addLink(resword.getString("create_new_filter"), "CreateFiltersOne?action=begin");
            table.setQuery("CreateFiltersOne", new HashMap<>());
            table.setRows(filterRows);
            table.computeDisplay();

            request.setAttribute("table", table);
            // the code above replaces the following line:

            // request.setAttribute("filters",filters);
            forwardPage(Page.CREATE_FILTER_SCREEN_1);
        } else if ("begin".equalsIgnoreCase(action)) {
            forwardPage(Page.CREATE_FILTER_SCREEN_2);
        }

    }

    @Override
    public void mayProceed() throws InsufficientPermissionException {

        locale = LocaleResolver.getLocale(request);
        // < resword =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.words",locale);
        // < restext =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes",locale);
        // < respage =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.page_messages",locale);
        // <
        // resexception=ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.exceptions",locale);

        if (ub.isSysAdmin()) {
            return;
        }
        if (currentRole.getRole().equals(Role.STUDYDIRECTOR) || currentRole.getRole().equals(Role.COORDINATOR)
            || currentRole.getRole().equals(Role.INVESTIGATOR)) {
            return;
        }

        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.MENU, resexception.getString("not_allowed_access_extract_data_servlet"), "1");

    }
}
