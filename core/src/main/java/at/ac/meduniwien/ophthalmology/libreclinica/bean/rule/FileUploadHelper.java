/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.rule;

import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;
import at.ac.meduniwien.ophthalmology.libreclinica.service.io.SecureFilePaths;
import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileUploadByteCountLimitException;
import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletDiskFileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;

@SuppressWarnings("all")

public class FileUploadHelper {

    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    FileProperties fileProperties;
    FileRenamePolicy fileRenamePolicy;
    

    public FileUploadHelper() {
        fileProperties = new FileProperties();
	}

    public FileUploadHelper(FileProperties fileProperties) {
        super();
        this.fileProperties = fileProperties;
    }

    public List<File> returnFiles(HttpServletRequest request, ServletContext context) {

        // Check that we have a file upload request
        boolean isMultipart = JakartaServletDiskFileUpload.isMultipartContent(request);
        return isMultipart ? getFiles(request, context, null) : new ArrayList<File>();
    }

    public List<File> returnFiles(HttpServletRequest request, ServletContext context, FileRenamePolicy fileRenamePolicy) {

        // Check that we have a file upload request
        this.fileRenamePolicy = fileRenamePolicy;
        boolean isMultipart = JakartaServletDiskFileUpload.isMultipartContent(request);
        return isMultipart ? getFiles(request, context, null) : new ArrayList<File>();
    }

    public List<File> returnFiles(HttpServletRequest request, ServletContext context, String dirToSaveUploadedFileIn, FileRenamePolicy fileRenamePolicy) {

        // Check that we have a file upload request
        this.fileRenamePolicy = fileRenamePolicy;
        boolean isMultipart = JakartaServletDiskFileUpload.isMultipartContent(request);
        return isMultipart ? getFiles(request, context, createDirectoryIfDoesntExist(dirToSaveUploadedFileIn)) : new ArrayList<File>();
    }

    public List<File> returnFiles(HttpServletRequest request, ServletContext context, String dirToSaveUploadedFileIn) {

        // Check that we have a file upload request
        boolean isMultipart = JakartaServletDiskFileUpload.isMultipartContent(request);
        return isMultipart ? getFiles(request, context, createDirectoryIfDoesntExist(dirToSaveUploadedFileIn)) : new ArrayList<File>();
    }

    private List<File> getFiles(HttpServletRequest request, ServletContext context, String dirToSaveUploadedFileIn) {
        List<File> files = new ArrayList<File>();

        DiskFileItemFactory factory = DiskFileItemFactory.builder().get();
        JakartaServletDiskFileUpload upload = new JakartaServletDiskFileUpload(factory);
        upload.setFileSizeMax(getFileProperties().getFileSizeMax());
        try {
            List<DiskFileItem> items = upload.parseRequest(request);
            Iterator<DiskFileItem> iter = items.iterator();
            while (iter.hasNext()) {
                DiskFileItem item = iter.next();

                if (item.isFormField()) {
                    request.setAttribute(item.getFieldName(), item.getString());
                } else {
                    getFileProperties().isValidExtension(item.getName());
                	files.add(processUploadedFile(item, dirToSaveUploadedFileIn));
                }
            }
            return files;
        }catch (FileUploadByteCountLimitException slee) {
            throw new OpenClinicaSystemException("exceeds_permitted_file_size", new Object[] { String.valueOf(getFileProperties().getFileSizeMaxInMb()) },
                    slee.getMessage());
		}catch (FileUploadException fue) {
            throw new OpenClinicaSystemException("file_upload_error_occured", new Object[] { fue.getMessage() }, fue.getMessage());
        } catch (IOException ioe) {
            throw new OpenClinicaSystemException("file_upload_error_occured", new Object[] { ioe.getMessage() }, ioe.getMessage());
        }
    }

    /**
     * The directory used when a caller names none, created so that only this
     * process's user can read it.
     *
     * <p>The fallback used to be {@code java.io.tmpdir} itself, which is shared
     * and world-readable on a normal POSIX host, so an upload that arrived
     * without a target directory left its contents readable by every local
     * account. The CRF data import reaches this path: it calls returnFiles
     * without a directory, and what it uploads is clinical data.
     *
     * <p>{@link Files#createTempDirectory} applies owner-only permissions where
     * the platform has them, so the file below inherits a directory nobody else
     * can traverse. One directory is made per call and the caller owns what it
     * gets back, as before.
     */
    // package-private so FileUploadHelperTempDirectoryTest can assert the permissions
    File privateUploadDirectory() throws IOException {
        return Files.createTempDirectory("libreclinica-upload-").toFile();
    }

    /**
     * Writes one uploaded part into the upload directory.
     *
     * <p>The multipart name is the client's, so only its last segment is taken
     * as a file name, and the file that segment produces has to sit inside the
     * directory. A name failing either test aborts the upload rather than
     * yielding a file: the caller collects the return value straight into the
     * list it hands back, which has no room for a placeholder, and the
     * extension check in that same loop already fails the whole upload on a
     * name it rejects.
     */
    private File processUploadedFile(DiskFileItem item, String dirToSaveUploadedFileIn) throws IOException {
        File directory = dirToSaveUploadedFileIn == null
                ? privateUploadDirectory()
                : new File(dirToSaveUploadedFileIn);
        String fileName = SecureFilePaths.safeUploadName(item.getName());
        if (fileName == null) {
            throw new OpenClinicaSystemException("uploaded file has no usable file name");
        }

        File uploadedFile = new File(directory, fileName);
        if (!SecureFilePaths.isInside(uploadedFile, directory)) {
            throw new OpenClinicaSystemException("uploaded file name does not stay inside the upload directory");
        }
        if (fileRenamePolicy != null) {
        	try {
        		uploadedFile = fileRenamePolicy.rename(uploadedFile, item.getInputStream());
        	} catch (IOException e) {
        		throw new OpenClinicaSystemException(e.getMessage());
        	}
        }
        try {
			item.write(uploadedFile.toPath());
		} catch (Exception e) {
			throw new OpenClinicaSystemException(e.getMessage());
		}
        return uploadedFile;

    }

    private String createDirectoryIfDoesntExist(String theDir) {
        if (!new File(theDir).isDirectory()) {
            new File(theDir).mkdirs();
        }
        return new File(theDir).toString();
    }

    public FileProperties getFileProperties() {
        return fileProperties;
    }

    public void setFileProperties(FileProperties fileProperties) {
        this.fileProperties = fileProperties;
    }

}
