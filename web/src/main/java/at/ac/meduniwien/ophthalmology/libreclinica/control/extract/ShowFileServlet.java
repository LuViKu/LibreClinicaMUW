/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.extract;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ArchivedDatasetFileBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ArchivedDatasetFileDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.core.LocaleResolver;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.ArchivedDatasetFileRow;
import at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * <P>
 * purpose of this servlet is to respond with a file listing after we've
 * outlasted the 'please wait' message.
 *
 * @author thickerson
 * Created on Jun 9, 2005
 */
public class ShowFileServlet extends SecureController {

    /**
	 * 
	 */
	private static final long serialVersionUID = 4497795033976495218L;
	Locale locale;

    // < ResourceBundlerestext,resword,respage,resexception;

    public static String getLink(int fId, int dId) {
        return "ShowFile?fileId=" + fId + "&datasetId=" + dId;
    }

    @Override
    public void processRequest() throws Exception {
        FormProcessor fp = new FormProcessor(request);
        int fileId = fp.getInt("fileId");
        int dsId = fp.getInt("datasetId");
        DatasetDAO dsdao = new DatasetDAO(sm.getDataSource());
        DatasetBean db = (DatasetBean) dsdao.findByPK(dsId);

        ArchivedDatasetFileDAO asdfdao = new ArchivedDatasetFileDAO(sm.getDataSource());
        ArchivedDatasetFileBean asdfBean = (ArchivedDatasetFileBean) asdfdao.findByPK(fileId);

        ArrayList<ArchivedDatasetFileBean> newFileList = new ArrayList<>();
        newFileList.add(asdfBean);

        ArrayList<ArchivedDatasetFileRow> filterRows = ArchivedDatasetFileRow.generateRowsFromBeans(newFileList);
        EntityBeanTable table = fp.getEntityBeanTable();
        String[] columns =
            { resword.getString("file_name"), resword.getString("run_time"), resword.getString("file_size"), resword.getString("created_date"),
                resword.getString("created_by") };

        table.setColumns(new ArrayList<String>(Arrays.asList(columns)));
        table.hideColumnLink(0);
        table.hideColumnLink(1);
        table.hideColumnLink(2);
        table.hideColumnLink(3);
        table.hideColumnLink(4);

        // table.setQuery("ExportDataset?datasetId=" +db.getId(), new
        // HashMap());
        // trying to continue...
        // session.setAttribute("newDataset",db);
        request.setAttribute("dataset", db);
        request.setAttribute("file", asdfBean);
        table.setRows(filterRows);
        table.computeDisplay();

        request.setAttribute("table", table);
        Page finalTarget = Page.EXPORT_DATA_CUSTOM;

        finalTarget.setFileName("/WEB-INF/jsp/extract/generateMetadataFile.jsp");

        forwardPage(finalTarget);
    }

    @Override
    public void mayProceed() throws InsufficientPermissionException {

        locale = LocaleResolver.getLocale(request);
        // < restext =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes",locale);
        // < resword =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.words",locale);
        // < respage =
        // ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.page_messages",locale);
        // <
        // resexception=ResourceBundle.getBundle("at.ac.meduniwien.ophthalmology.libreclinica.i18n.exceptions",locale);

        if (ub.isSysAdmin()) {
            return;
        }
        if (currentRole.getRole().equals(Role.STUDYDIRECTOR) || currentRole.getRole().equals(Role.COORDINATOR)
            || currentRole.getRole().equals(Role.INVESTIGATOR) || currentRole.getRole().equals(Role.MONITOR)) {
            return;
        }

        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.MENU, resexception.getString("not_allowed_access_extract_data_servlet"), "1");

    }

}
