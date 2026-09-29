/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.control.core.SecureController;
import at.ac.meduniwien.ophthalmology.libreclinica.control.form.FormProcessor;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;
import at.ac.meduniwien.ophthalmology.libreclinica.web.InsufficientPermissionException;
import at.ac.meduniwien.ophthalmology.libreclinica.web.job.ImportSpringJob;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;

/**
 * view Import File Server, by Tom Hickerson, 2010
 * 
 * @author thickerson, purpose is to be able to show an external file in a log to a user
 * 
 */
@SuppressWarnings("all")
public class ViewLogMessageServlet extends SecureController {

    /**
	 * 
	 */
	private static final long serialVersionUID = 5409165798175683504L;
	private static final String LOG_MESSAGE = "logmsg";
    private static final String FILE_NAME = "filename";
    private static final String TRIGGER_NAME = "tname";
    private static final String GROUP_NAME = "gname";

    @Override
    protected void mayProceed() throws InsufficientPermissionException {
        if (ub.isSysAdmin()) {
            return;
        }
        if (currentRole.getRole().equals(Role.STUDYDIRECTOR) || currentRole.getRole().equals(Role.COORDINATOR) || currentRole.getRole().equals(Role.ADMIN)
            || currentRole.getRole().equals(Role.INVESTIGATOR)) {// ?

            return;
        }

        addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
        throw new InsufficientPermissionException(Page.MENU, resexception.getString("not_allowed_access_extract_data_servlet"), "1");

        // allow only admin-level users, currently

    }

    @Override
    protected void processRequest() throws Exception {
        try {
            File destDirectory = new File(ImportSpringJob.IMPORT_DIR_2);
            FormProcessor fp = new FormProcessor(request);
            String fileName = fp.getString("n");
            String triggerName = fp.getString("tn");
            String groupName = fp.getString("gn");
            logger.debug("found trigger name " + triggerName + " group name " + groupName);
            File logDestDirectory = resolveLogFile(destDirectory, fileName);
            if (logDestDirectory == null) {
                throw new FileNotFoundException("import log name is not inside the import directory");
            }
            // StringBuffer sbu = new StringBuffer();
            // BufferedReader r = new BufferedReader(new FileReader(logDestDirectory));
            // char[] buffer = new char[1024];
            // int amount = 0;
            // while ((amount = r.read(buffer, 0, buffer.length)) != -1) {
            // sbu.append(buffer);
            // }
            // r.close();
            String fileContents = readFromFile(logDestDirectory);
            request.setAttribute(ViewLogMessageServlet.LOG_MESSAGE, fileContents);
            request.setAttribute(ViewLogMessageServlet.FILE_NAME, fileName);
            request.setAttribute(ViewLogMessageServlet.TRIGGER_NAME, triggerName);
            request.setAttribute(ViewLogMessageServlet.GROUP_NAME, groupName);
            // need to also set the information back to the original view jobs
            // so we have to get back to this type of page:
            // http://localhost:8081/OpenClinica-3.0-SNAPSHOT/ViewSingleJob?tname=test%20job%2001&gname=1
            forwardPage(Page.VIEW_LOG_MESSAGE);
        } catch (Exception e) {
            logger.error("found IO exception: " + e.getMessage());
            addPageMessage(respage.getString("no_have_correct_privilege_current_study") + respage.getString("change_study_contact_sysadmin"));
            // throw new InsufficientPermissionException(Page.MENU, resexception.getString("not_allowed_access_extract_data_servlet"), "1");
            forwardPage(Page.MENU);
        }
    }

    /**
     * The import job writes each run's log to
     * {@code <import dir>/<yyyy>/<MM>/<dd>/<HHmmssSSS>/<file name>.log.txt/log.txt}
     * and links to it with {@code n=<yyyy>/<MM>/<dd>/<HHmmssSSS>/<file name>},
     * so the name legitimately contains separators. It must not climb out of
     * the import directory: {@code ..} segments are refused and the resolved
     * path has to stay below the directory.
     *
     * @return the log file, or {@code null} when the name is unusable
     */
    static File resolveLogFile(File importDirectory, String name) throws IOException {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            return null;
        }
        String cleaned = name.replaceAll("\\s+", "_");
        for (String segment : cleaned.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                return null;
            }
        }
        File logFile = new File(importDirectory + File.separator + cleaned + ".log.txt" + File.separator + "log.txt");
        Path base = importDirectory.getCanonicalFile().toPath();
        Path resolved = logFile.getCanonicalFile().toPath();
        return resolved.startsWith(base) ? logFile : null;
    }

    public static String readFromFile(File filename) throws java.io.FileNotFoundException, java.io.IOException {

        StringBuffer readBuffer = new StringBuffer();
        BufferedReader fileReader = new BufferedReader(new FileReader(filename));

        char[] readChars = new char[1024];
        int count;
        while ((count = fileReader.read(readChars)) >= 0) {
            readBuffer.append(readChars, 0, count);
        }
        fileReader.close();
        return readBuffer.toString();

    }

}
