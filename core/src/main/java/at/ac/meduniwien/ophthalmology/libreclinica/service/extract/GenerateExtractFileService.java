/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.zip.ZipOutputStream;

import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ArchivedDatasetFileBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DisplayItemHeaderBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExportFormatBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.ExtractBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.SPSSReportBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.SPSSVariableNameValidator;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.TabReportBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemFormMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.ArchivedDatasetFileDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemFormMetadataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("all")
public class GenerateExtractFileService {

    private static final Logger logger = LoggerFactory.getLogger(GenerateExtractFileService.class);
    private final DataSource ds;
    private final CoreResources coreResources;
    private final RuleSetRuleDao ruleSetRuleDao;
    private HttpServletRequest request;
    public static ResourceBundle resword;

    private static List<File> oldFiles = new LinkedList<File>();

    public GenerateExtractFileService(DataSource ds, HttpServletRequest request, CoreResources coreResources,
            RuleSetRuleDao ruleSetRuleDao) {
        this.ds = ds;
        this.request = request;
        this.coreResources = coreResources;
        this.ruleSetRuleDao = ruleSetRuleDao;
    }

    public GenerateExtractFileService(DataSource ds, CoreResources coreResources,RuleSetRuleDao ruleSetRuleDao) {
        this(ds, null, coreResources, ruleSetRuleDao);
    }

    public void setUpResourceBundles() {
        Locale locale;
        try {
            locale = request.getLocale();
        } catch (NullPointerException ne) {
            locale = Locale.of("en-US");
        }

        ResourceBundleProvider.updateLocale(locale);
        resword = ResourceBundleProvider.getWordsBundle(locale);
    }

    /**
     * createTabFile, added by tbh, 01/2009
     */
    public HashMap<String, Integer> createTabFile(ExtractBean eb, long sysTimeBegin, String generalFileDir, DatasetBean datasetBean, int activeStudyId,
            int parentStudyId, String generalFileDirCopy, UserAccountBean userBean) {

        TabReportBean answer = new TabReportBean();

        DatasetDAO dsdao = new DatasetDAO(ds);
        // create the extract bean here, tbh
        eb = dsdao.getDatasetData(eb, activeStudyId, parentStudyId);
        eb.getMetadata();
        eb.computeReport(answer);

        long sysTimeEnd = System.currentTimeMillis() - sysTimeBegin;
        String TXTFileName = datasetBean.getName() + "_tab.xls";

        int fId = this.createFile(TXTFileName, generalFileDir, answer.toString(), datasetBean, sysTimeEnd, ExportFormatBean.TXTFILE, true, userBean);
        if (!"".equals(generalFileDirCopy)) {
            this.createFile(TXTFileName, generalFileDirCopy, answer.toString(), datasetBean, sysTimeEnd, ExportFormatBean.TXTFILE, false, userBean);
        }
        logger.info("created txt file");
        // return TXTFileName;
        HashMap<String, Integer> answerMap = new HashMap<>();
        answerMap.put(TXTFileName, Integer.valueOf(fId));
        return answerMap;
    }

    /**
     * createODMFile, added by tbh, 09/2010 - note that this is created to be backwards-compatible with previous versions of OpenClinica-web.
     * i.e. we remove the boolean zipped variable.
     */
    public HashMap<String, Integer> createODMFile(String odmVersion, long sysTimeBegin, String generalFileDir, DatasetBean datasetBean,
            StudyBean currentStudy, String generalFileDirCopy,ExtractBean eb,
            Integer currentStudyId, Integer parentStudyId, String studySubjectNumber, UserAccountBean userBean) {
        // 2026-09-18 — zipped=false, deliberately.
        //
        // OdmFileCreation does not zip: the call that would is commented out
        // there ("Zipped in the next stage"), because the Quartz XsltTransformJob
        // zips the ODM itself after its stylesheet run. It passes zipped through
        // for that later stage. Callers of THIS bridge (the SPA's dataset export,
        // the async export materializer, the legacy /ExportDataset servlet) have
        // no such later stage, so passing true only made the archived_dataset_file
        // row claim a "<name>.xml.zip" that was never written — the export
        // appeared to succeed and its download was a dead link. Recording the
        // file that actually exists makes the download work.
        return createODMFile(odmVersion, sysTimeBegin, generalFileDir, datasetBean,
                currentStudy, generalFileDirCopy, eb, currentStudyId, parentStudyId, studySubjectNumber, false, true, true, null, userBean);
    }
    /**
     * createODMfile, added by tbh, 01/2009
     * @deprecated Use {@link OdmFileCreation#createODMFile} instead
     */
    @Deprecated
    public HashMap<String, Integer> createODMFile(String odmVersion, long sysTimeBegin, String generalFileDir, DatasetBean datasetBean,
            StudyBean currentStudy, String generalFileDirCopy,ExtractBean eb,
            Integer currentStudyId, Integer parentStudyId, String studySubjectNumber, boolean zipped, boolean saveToDB, boolean deleteOld, String odmType, UserAccountBean userBean){

        // OdmFileCreation has no DI-aware constructor, so its three
        // collaborators are set explicitly here.
        //
        // 2026-09-18: this used to forward only `ds`. The constructor
        // accepted coreResources + ruleSetRuleDao and silently dropped
        // them, leaving both null on every ODM export that does not go
        // through the Quartz XsltTransformJob (which resolves the
        // fully-wired `odmFileCreation` bean itself) — i.e. the SPA's
        // Quick-ODM, POST /datasets/{id}/export?format=odm, the async
        // export materializer and the legacy /ExportDataset servlet.
        // MetadataUnit.collectMetaDataVersion dereferences the rules dao,
        // so that was a latent NPE; all four callers already pass both
        // dependencies in, they just needed keeping.
        //
        // Scope note: wiring these does NOT by itself make ODM export
        // work for the Liquibase-seeded CRFs. Those carry width_decimal
        // in "(w,d)" form while OdmExtractDAO.parseDecimal expects
        // OpenClinica's "w(d)", so metadata collection throws
        // NumberFormatException before the rules lookup is ever reached
        // (verified 2026-09-18 by running the export with and without
        // this change — see DatasetExportCharacterisationDatabaseIT).
        OdmFileCreation ofc = new OdmFileCreation();
        ofc.setDataSource(ds);
        ofc.setCoreResources(coreResources);
        ofc.setRuleSetRuleDao(ruleSetRuleDao);
        return ofc.createODMFile(odmVersion, sysTimeBegin, generalFileDir, datasetBean,
                currentStudy, generalFileDirCopy, eb,
                currentStudyId, parentStudyId, studySubjectNumber, zipped, saveToDB, deleteOld, odmType, userBean);
    }

    public List<File> getOldFiles(){
        return oldFiles;
    }

    /**
     * createSPSSFile, added by tbh, 01/2009
     *
     * @param db
     * @param eb
     * @param currentstudyid
     * @param parentstudy
     * @return
     */
	public HashMap<String, Integer> createSPSSFile(DatasetBean db, ExtractBean eb2, StudyBean currentStudy, StudyBean parentStudy, long sysTimeBegin,
            String generalFileDir, SPSSReportBean answer, String generalFileDirCopy, UserAccountBean userBean) {
        setUpResourceBundles();

        String SPSSFileName = db.getName() + "_data_spss.dat";
        String DDLFileName = db.getName() + "_ddl_spss.sps";
        String ZIPFileName = db.getName() + "_spss";

        SPSSVariableNameValidator svnv = new SPSSVariableNameValidator();
        answer.setDatFileName(SPSSFileName);
        // DatasetDAO dsdao = new DatasetDAO(ds);

        // create the extract bean here, tbh
        // ExtractBean eb = this.generateExtractBean(db, currentStudy,
        // parentStudy);

        // eb = dsdao.getDatasetData(eb, currentStudy.getId(),
        // parentStudy.getId());

        // eb.getMetadata();

        // eb.computeReport(answer);

        answer.setItems(eb2.getItemNames());// set up items here to get
        // itemMetadata

        // set up response sets for each item here
        ItemFormMetadataDAO imfdao = new ItemFormMetadataDAO(ds);
        ArrayList<DisplayItemHeaderBean> items = answer.getItems();
        for (int i = 0; i < items.size(); i++) {
            DisplayItemHeaderBean dih = (DisplayItemHeaderBean) items.get(i);
            ItemBean item = dih.getItem();
            ArrayList<ItemFormMetadataBean> metas = imfdao.findAllByItemId(item.getId());
            // for (int h = 0; h < metas.size(); h++) {
            // ItemFormMetadataBean ifmb = (ItemFormMetadataBean)
            // metas.get(h);
            // logger.info("group name found:
            // "+ifmb.getGroupLabel());
            // }
            // logger.info("crf versionname" +
            // meta.getCrfVersionName());
            item.setItemMetas(metas);

        }

        HashMap<String, String> eventDescs = new HashMap<>();

        eventDescs = eb2.getEventDescriptions();

        eventDescs.put("SubjID", resword.getString("study_subject_ID"));
        eventDescs.put("ProtocolID", resword.getString("protocol_ID_site_ID"));
        eventDescs.put("DOB", resword.getString("date_of_birth"));
        eventDescs.put("YOB", resword.getString("year_of_birth"));
        eventDescs.put("Gender", resword.getString("gender"));
        answer.setDescriptions(eventDescs);

        ArrayList<String> generatedReports = new ArrayList<>();
        try {
            // YW <<
            generatedReports.add(answer.getMetadataFile(svnv, eb2).toString());
            generatedReports.add(answer.getDataFile().toString());
            // YW >>
        } catch (IndexOutOfBoundsException i) {
            generatedReports.add(answer.getMetadataFile(svnv, eb2).toString());
            logger.debug("throw the error here");
        }

        long sysTimeEnd = System.currentTimeMillis() - sysTimeBegin;

        ArrayList<String> titles = new ArrayList<>();
        // YW <<
        titles.add(DDLFileName);
        titles.add(SPSSFileName);
        // YW >>

        // create new createFile method that accepts array lists to
        // put into zip files
        int fId = this.createFile(ZIPFileName, titles, generalFileDir, generatedReports, db, sysTimeEnd, ExportFormatBean.TXTFILE, true, userBean);
        if (!"".equals(generalFileDirCopy)) {
            this.createFile(ZIPFileName, titles, generalFileDirCopy, generatedReports, db, sysTimeEnd, ExportFormatBean.TXTFILE, false, userBean);
        }
        // return DDLFileName;
        HashMap<String, Integer> answerMap = new HashMap<>();
        answerMap.put(DDLFileName, Integer.valueOf(fId));
        return answerMap;
    }

    /**
     * SAS export: the ODM document plus the three packaged stylesheets.
     *
     * <p>Until 2026-09 every caller outside the Quartz scheduled-job screens
     * wrote a zero-byte file here — the SPA's dataset export, the asynchronous
     * export runner and the legacy Extract Data servlet all had a SAS branch
     * that produced an {@code archived_dataset_file} row over empty content. An
     * operator saw a successful export and downloaded nothing.
     *
     * <p>What SAS actually needs is three artefacts, and they are generated the
     * same way the scheduled job generates them (see {@code extract.10} in
     * extract.properties):
     *
     * <ul>
     *   <li>{@code SAS_DATA.xml} — the data, as an XML document</li>
     *   <li>{@code SAS_MAP.xml} — an SXLEMAP telling SAS how to read it</li>
     *   <li>{@code SAS_FORMAT.sas} — the syntax that reads both in and applies
     *       the code lists as SAS formats</li>
     * </ul>
     *
     * <p>The names are fixed rather than derived from the dataset, because
     * {@code xml_convert_sas_format.xsl} writes {@code FILENAME} statements
     * that name the other two files literally. Renaming an entry would produce
     * a script that cannot find its own data.
     *
     * <p>The intermediate ODM is generated with {@code odmType=clinical_data}
     * (what the stylesheets expect) and is deliberately not recorded in
     * {@code archived_dataset_file} — it is scaffolding, not a deliverable. It
     * is removed after the transform unless {@code dataset_file_delete} is
     * configured off, matching how the other multi-file exports treat their
     * intermediates.
     *
     * @return the archived_dataset_file id of the zip, or 0 if nothing was written
     */
    public int createSasFile(DatasetBean datasetBean, ExtractBean eb, StudyBean currentStudy,
            long sysTimeBegin, String generalFileDir, UserAccountBean userBean) {

        HashMap<String, Integer> odmAnswer = createODMFile(
                "oc1.3", sysTimeBegin, generalFileDir, datasetBean, currentStudy, "", eb,
                currentStudy.getId(), currentStudy.getParentStudyId(), "99",
                false /* zipped */, false /* saveToDB — scaffolding, not a deliverable */,
                false /* deleteOld */, "clinical_data", userBean);

        String odmName = null;
        if (odmAnswer != null && !odmAnswer.isEmpty()) {
            odmName = odmAnswer.keySet().iterator().next();
        }
        if (odmName == null || odmName.isBlank()) {
            logger.error("SAS export: ODM generation produced no file name for dataset {}", datasetBean.getId());
            return 0;
        }
        File odmFile = new File(generalFileDir, odmName.replaceAll(" ", "_"));

        ArrayList<String> contents;
        try {
            contents = new ArrayList<>(OdmXsltTransformer.transform(odmFile, SAS_STYLESHEETS));
        } catch (Exception e) {
            // Deliberately not swallowed into an empty export: a failed
            // transform must not look like a successful one. The caller turns
            // a 0 return into an error for the operator.
            logger.error("SAS export: stylesheet run failed for dataset " + datasetBean.getId(), e);
            deleteIntermediate(odmFile);
            return 0;
        }

        long sysTimeEnd = System.currentTimeMillis() - sysTimeBegin;
        int fId = createFile(datasetBean.getName() + "_sas", new ArrayList<>(SAS_EXPORT_NAMES),
                generalFileDir, contents, datasetBean, sysTimeEnd, ExportFormatBean.TXTFILE, true, userBean);
        deleteIntermediate(odmFile);
        return fId;
    }

    /** Stylesheets for the SAS export — mirrors {@code extract.10.file}. */
    private static final List<String> SAS_STYLESHEETS = List.of(
            "xml_convert_sas_map.xsl", "xml_convert_sas_data.xsl", "xml_convert_sas_format.xsl");

    /** Zip entry names — mirrors {@code extract.10.exportname}; see createSasFile. */
    private static final List<String> SAS_EXPORT_NAMES = List.of(
            "SAS_MAP.xml", "SAS_DATA.xml", "SAS_FORMAT.sas");

    private static void deleteIntermediate(File f) {
        String flag = CoreResources.getField("dataset_file_delete");
        if (flag != null && "false".equalsIgnoreCase(flag.trim())) {
            return;
        }
        if (f != null && f.isFile() && !f.delete()) {
            logger.warn("could not delete intermediate extract file {}", f.getName());
        }
    }

    public int createFile(String zipName, ArrayList<String> names, String dir, ArrayList<String> contents, DatasetBean datasetBean, long time,
            ExportFormatBean efb, boolean saveToDB, UserAccountBean userBean) {
        ArchivedDatasetFileBean fbFinal = new ArchivedDatasetFileBean();
        // >> tbh #4915
        zipName = zipName.replaceAll(" ", "_");
        fbFinal.setId(0);
        BufferedWriter w = null;
        try {
            File complete = new File(dir);
            if (!complete.isDirectory()) {
                complete.mkdirs();
            }
            int totalSize = 0;
            ZipOutputStream z = new ZipOutputStream(new FileOutputStream(new File(complete, zipName + ".zip")));
            FileInputStream is = null;
            for (int i = 0; i < names.size(); i++) {
                String name = (String) names.get(i);
                // >> tbh #4915
                name = name.replaceAll(" ", "_");
                String content = (String) contents.get(i);
                File newFile = new File(complete, name);
                // totalSize = totalSize + (int)newFile.length();
                newFile.setLastModified(System.currentTimeMillis());

                 w = new BufferedWriter(new FileWriter(newFile));
                w.write(content);
                w.close();
                logger.info("finished writing the text file...");
                // now, we write the file to the zip file
                is = new FileInputStream(newFile);

                logger.info("created zip output stream...");

                z.putNextEntry(new java.util.zip.ZipEntry(name));

                int bytesRead;
                byte[] buff = new byte[512];

                while ((bytesRead = is.read(buff)) != -1) {
                    z.write(buff, 0, bytesRead);
                    totalSize += 512;
                }
                z.closeEntry();
                //A. Hamid. 4910
                is.close();
                if(CoreResources.getField("dataset_file_delete").equalsIgnoreCase("true")
                        || CoreResources.getField("dataset_file_delete").equals("")){
                    newFile.delete();
                }




            }
            logger.info("writing buffer...");
            // }
            z.flush();
            z.finish();
            z.close();

            if (is != null) {
                try {
                    is.close();
                } catch (java.io.IOException ie) {
                    ie.printStackTrace();
                }
            }
            logger.info("finished zipping up file...");
            // set up the zip to go into the database
            if (saveToDB) {
                ArchivedDatasetFileBean fb = new ArchivedDatasetFileBean();
                fb.setName(zipName + ".zip");
                fb.setFileReference(dir + zipName + ".zip");
                // current location of the file on the system
                fb.setFileSize(totalSize);
                // set the above to compressed size?
                fb.setRunTime((int) time);
                // need to set this in milliseconds, get it passed from above
                // methods?
                fb.setDatasetId(datasetBean.getId());
                fb.setExportFormatBean(efb);
                fb.setExportFormatId(efb.getId());
                fb.setOwner(userBean);
                fb.setOwnerId(userBean.getId());
                fb.setDateCreated(new Date(System.currentTimeMillis()));

                boolean write = true;
                ArchivedDatasetFileDAO asdfDAO = new ArchivedDatasetFileDAO(ds);

                if (write) {
                    fbFinal = (ArchivedDatasetFileBean) asdfDAO.create(fb);
                    logger.info("Created ADSFile!: " + fbFinal.getId() + " for " + zipName + ".zip");
                } else {
                    logger.info("duplicate found: " + fb.getName());
                }
            }
            // created in database!

        } catch (Exception e) {
            logger.warn(e.getMessage());
            e.printStackTrace();
        }
        finally{
            if(w!=null)
                try {
                    w.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
        return fbFinal.getId();
    }

    public int createFileK(String name, String dir, String content,
            DatasetBean datasetBean, long time, ExportFormatBean efb,
            boolean saveToDB, boolean zipped, boolean deleteOld, UserAccountBean userBean) {
        ArchivedDatasetFileBean fbFinal = new ArchivedDatasetFileBean();
        // >> tbh 04/2010 #4915 replace all names' spaces with underscores
        name = name.replaceAll(" ", "_");
        fbFinal.setId(0);
        BufferedWriter w =null;
        try {



            File complete = new File(dir);
            if (!complete.isDirectory()) {
                complete.mkdirs();
            }

//            else  if(deleteOld)// so directory exists check if the files are there
//            {
//            	deleteDirectory(complete);
//            }

            //File newFile = new File(complete, name);
            //newFile.setLastModified(System.currentTimeMillis());

            File oldFile = new File(complete, name);
            File newFile = null;
            if (oldFile.exists()) {
                newFile = oldFile;
                if(oldFiles!=null || !oldFiles.isEmpty() )
                oldFiles.remove(oldFile);
            } else {
                newFile = new File(complete, name);
            }

            //File
            newFile.setLastModified(System.currentTimeMillis());

            w = new BufferedWriter(new FileWriter(newFile, true));
            w.write(content);
            w.close();
            logger.info("finished writing the text file...");
            // set up the zip to go into the database
            if (saveToDB) {
                ArchivedDatasetFileBean fb = new ArchivedDatasetFileBean();
                if (zipped) {
                    fb.setName(name + ".zip");
                    fb.setFileReference(dir + name + ".zip");
                } else {
                    fb.setName(name);
                    fb.setFileReference(dir + name);
                }
                // logger.info("ODM filename: " + name + ".zip");

                // logger.info("ODM fileReference: " + dir + name + ".zip");
                // current location of the file on the system
                fb.setFileSize((int) newFile.length());
                // logger.info("ODM setFileSize: " + (int)newFile.length() );
                // set the above to compressed size?
                fb.setRunTime((int) time);
                // logger.info("ODM setRunTime: " + (int)time );
                // need to set this in milliseconds, get it passed from above
                // methods?
                fb.setDatasetId(datasetBean.getId());
                // logger.info("ODM setDatasetid: " + ds.getId() );
                fb.setExportFormatBean(efb);
                // logger.info("ODM setExportFormatBean: success" );
                fb.setExportFormatId(efb.getId());
                // logger.info("ODM setExportFormatId: " + efb.getId());
                fb.setOwner(userBean);
                // logger.info("ODM setOwner: " + sm.getUserBean());
                fb.setOwnerId(userBean.getId());
                // logger.info("ODM setOwnerId: " + sm.getUserBean().getId() );
                fb.setDateCreated(new Date(System.currentTimeMillis()));
                boolean write = true;
                ArchivedDatasetFileDAO asdfDAO = new ArchivedDatasetFileDAO(ds);
                // eliminating all checks so that we create multiple files, tbh 6-7
                if (write) {
                    fbFinal = (ArchivedDatasetFileBean) asdfDAO.create(fb);
                } else {
                    logger.info("duplicate found: " + fb.getName());
                }
            }
            // created in database!

        } catch (Exception e) {
            logger.error(e.getMessage());
            e.printStackTrace();
        }
        finally{
            if(w!=null)
                try {
                    w.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
        return fbFinal.getId();
    }

    public int createFile(String name, String dir, String content, DatasetBean datasetBean, long time,
            ExportFormatBean efb, boolean saveToDB, UserAccountBean userBean) {
        ArchivedDatasetFileBean fbFinal = new ArchivedDatasetFileBean();
        // >> tbh 04/2010 #4915 replace all names' spaces with underscores
        name = name.replaceAll(" ", "_");
        fbFinal.setId(0);
        try {
            File complete = new File(dir);
            if (!complete.isDirectory()) {
                complete.mkdirs();
            }
            File newFile = new File(complete, name);
            newFile.setLastModified(System.currentTimeMillis());

            try (BufferedWriter w = new BufferedWriter(new FileWriter(newFile))) {
            	w.write(content);
            	w.close();
            }
            logger.info("finished writing the text file...");
            // now, we write the file to the zip file
            try (
            		FileInputStream is = new FileInputStream(newFile);
            		ZipOutputStream z = new ZipOutputStream(new FileOutputStream(new File(complete, name + ".zip")));
			) {
	            logger.info("created zip output stream...");
	            // we write over the content no matter what
	            // we then check to make sure there are no duplicates
	            // z.write(content);
	            z.putNextEntry(new java.util.zip.ZipEntry(name));
	            // int length = (int) newFile.length();
	            int bytesRead;
	            byte[] buff = new byte[512];
	            // read from buffered input stream and put into zip file
	            // while (-1 != (bytesRead = bis.read(buff, 0, buff.length))) {
	            while ((bytesRead = is.read(buff)) != -1) {
	                z.write(buff, 0, bytesRead);
	            }
	            logger.info("writing buffer...");
	            // }
	            z.closeEntry();
	            z.finish();
	            // newFile = new File(complete, name+".zip");
	            // newFile.setLastModified(System.currentTimeMillis());
	            //
	            // BufferedWriter w2 = new BufferedWriter(new FileWriter(newFile));
	            // w2.write(newOut.toString());
	            // w2.close();
	            if (is != null) {
	                try {
	                    is.close();
	                } catch (java.io.IOException ie) {
	                    ie.printStackTrace();
	                }
	            }
            }
            logger.info("finished zipping up file...");
            // set up the zip to go into the database
            if (saveToDB) {
                ArchivedDatasetFileBean fb = new ArchivedDatasetFileBean();
                fb.setName(name + ".zip");
                // logger.info("ODM filename: " + name + ".zip");
                fb.setFileReference(dir + name + ".zip");
                // logger.info("ODM fileReference: " + dir + name + ".zip");
                // current location of the file on the system
                fb.setFileSize((int) newFile.length());
                // logger.info("ODM setFileSize: " + (int)newFile.length() );
                // set the above to compressed size?
                fb.setRunTime((int) time);
                // logger.info("ODM setRunTime: " + (int)time );
                // need to set this in milliseconds, get it passed from above
                // methods?
                fb.setDatasetId(datasetBean.getId());
                // logger.info("ODM setDatasetid: " + ds.getId() );
                fb.setExportFormatBean(efb);
                // logger.info("ODM setExportFormatBean: success" );
                fb.setExportFormatId(efb.getId());
                // logger.info("ODM setExportFormatId: " + efb.getId());
                fb.setOwner(userBean);
                // logger.info("ODM setOwner: " + sm.getUserBean());
                fb.setOwnerId(userBean.getId());
                // logger.info("ODM setOwnerId: " + sm.getUserBean().getId() );
                fb.setDateCreated(new Date(System.currentTimeMillis()));
                boolean write = true;
                ArchivedDatasetFileDAO asdfDAO = new ArchivedDatasetFileDAO(ds);
                // eliminating all checks so that we create multiple files, tbh 6-7
                if (write) {
                    fbFinal = (ArchivedDatasetFileBean) asdfDAO.create(fb);
                } else {
                    logger.info("duplicate found: " + fb.getName());
                }
            }
            // created in database!

        } catch (Exception e) {
            logger.error("-- exception thrown at createFile: " + e.getMessage());
            e.printStackTrace();
        }

        return fbFinal.getId();
    }

    public ExtractBean generateExtractBean(DatasetBean dsetBean, StudyBean currentStudy, StudyBean parentStudy) {
        ExtractBean eb = new ExtractBean(ds);
        eb.setDataset(dsetBean);
        eb.setShowUniqueId(CoreResources.getField("show_unique_id"));
        eb.setStudy(currentStudy);
        eb.setParentStudy(parentStudy);
        eb.setDateCreated(new java.util.Date());

        // 2026-09-18 — resolve the dataset's saved item filters into the subject
        // set the extract restricts to. Every producer builds its ExtractBean
        // here, so doing it once covers ODM, tab, CSV and SPSS alike. Null means
        // "no filters"; see EntityDAO.genDatabaseDateConstraint for where the
        // restriction is applied.
        if (dsetBean != null && dsetBean.getId() > 0 && currentStudy != null) {
            int scopeStudyId = currentStudy.getParentStudyId() > 0
                    ? currentStudy.getParentStudyId() : currentStudy.getId();
            dsetBean.setFilterSubjectIds(
                    new DatasetFilterSubjectResolver(ds).resolve(dsetBean.getId(), scopeStudyId));
        }
        return eb;
    }

    /**
     * To zip the xml files and delete the intermediate files.
     * @param name
     * @param dir
     * @throws IOException
     */

    public void zipFile(String name, String dir) throws IOException
    {
        File complete = new File(dir);
        if (!complete.isDirectory()) {
            complete.mkdirs();
        }

        File[] interXMLS = complete.listFiles();
        List<File> temp  = new LinkedList<File>(Arrays.asList(interXMLS));


        File oldFile = new File(complete, name);

        File newFile = null;
        if (oldFile.exists()) {
            newFile = oldFile;

        } else {
            newFile = new File(complete, name);
        }
            // now, we write the file to the zip file
            FileInputStream is = new FileInputStream(newFile);
            ZipOutputStream z = new ZipOutputStream(new FileOutputStream(new File(complete, name + ".zip")));
            if(oldFiles!=null || !oldFiles.isEmpty())
            {

                if(oldFiles.contains(new File(complete, name + ".zip")))
                {
                    oldFiles.remove(new File(complete, name + ".zip"));//Dont delete the files which u r just creating

                }
            }
            logger.info("created zip output stream...");
            // we write over the content no matter what
            // we then check to make sure there are no duplicates
            // z.write(content);
            z.putNextEntry(new java.util.zip.ZipEntry(name));
            // int length = (int) newFile.length();
            int bytesRead;
            byte[] buff = new byte[512];
            // read from buffered input stream and put into zip file
            // while (-1 != (bytesRead = bis.read(buff, 0, buff.length))) {
            while ((bytesRead = is.read(buff)) != -1) {
                z.write(buff, 0, bytesRead);
            }
            logger.info("writing buffer...");
            // }

            z.closeEntry();
            z.finish();
            if(z!=null)z.close();
            // newFile = new File(complete, name+".zip");
            // newFile.setLastModified(System.currentTimeMillis());
            //
            // BufferedWriter w2 = new BufferedWriter(new FileWriter(newFile));
            // w2.write(newOut.toString());
            // w2.close();
            if (is != null) {
                try {
                    is.close();
                } catch (java.io.IOException ie) {
                    ie.printStackTrace();
                }
            }
           //Adding the logic to delete the intermediate xmls
           oldFiles = temp;
            logger.info("finished zipping up file...");
       // }
    }

}
