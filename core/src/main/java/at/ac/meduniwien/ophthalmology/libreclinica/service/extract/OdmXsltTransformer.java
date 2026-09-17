/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * Runs the packaged extract stylesheets over a generated ODM document.
 *
 * <p>Until now the only code that could do this lived inside the Quartz
 * {@code XsltTransformJob}, which is reachable only through the legacy
 * "scheduled export job" screens. Everything else — the SPA's dataset export,
 * the asynchronous export runner, the legacy Extract Data servlet — had no way
 * to reach a stylesheet, which is why their SAS branch wrote an empty file.
 * Lifting the transform out of the job lets those paths produce the same
 * artefacts the scheduled job produces, from one implementation.
 *
 * <p><strong>Stylesheet lookup.</strong> At boot {@link CoreResources} copies
 * {@code classpath*:properties/xslt/*.xsl} into {@code ${filePath}/xslt}, and
 * that copy is what an administrator may edit — so it wins. When it is absent
 * (a fresh container whose copy step has not run, or a test that never boots
 * the application context) the packaged classpath resource is used instead,
 * rather than failing.
 *
 * <p><strong>Saxon, deliberately.</strong> {@code xml_convert_sas_map.xsl}
 * declares {@code version="2.0"}, so the JDK's built-in XSLT 1.0 processor
 * cannot run it. This uses the same Saxon factory the Quartz job has always
 * used, so output is identical between the two routes.
 *
 * <p><strong>Memory.</strong> Results are returned as strings because the
 * archive helper that zips them wants strings. The processor already builds
 * the whole ODM document in memory, so this adds no order of magnitude; the
 * export path as a whole is sized for one study's dataset, not a data lake.
 */
public final class OdmXsltTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(OdmXsltTransformer.class);

    /** Where CoreResources.copyBaseToDest puts the editable copies. */
    private static final String XSLT_SUBDIR = "xslt";

    /** Where the stylesheets are packaged inside the jar. */
    private static final String CLASSPATH_PREFIX = "properties/xslt/";

    private OdmXsltTransformer() {}

    /**
     * Applies each stylesheet to the same ODM document.
     *
     * @param odmXml     a generated ODM file (not zipped)
     * @param xslNames   stylesheet file names as they appear in
     *                   {@code extract.properties}, e.g.
     *                   {@code xml_convert_sas_map.xsl}
     * @return one result per stylesheet, in the order given
     * @throws IllegalArgumentException if the ODM file is missing or empty
     * @throws IOException              if a stylesheet cannot be read
     * @throws TransformerException     if a transform fails
     */
    public static List<String> transform(File odmXml, List<String> xslNames)
            throws IOException, TransformerException {

        if (odmXml == null || !odmXml.isFile()) {
            throw new IllegalArgumentException("ODM source is not a file: " + odmXml);
        }
        if (odmXml.length() == 0L) {
            throw new IllegalArgumentException("ODM source is empty: " + odmXml);
        }
        if (xslNames == null || xslNames.isEmpty()) {
            return List.of();
        }

        // net.sf.saxon rather than TransformerFactory.newInstance(): the
        // stylesheets are XSLT 2.0 and the platform default is 1.0.
        TransformerFactory factory = new net.sf.saxon.TransformerFactoryImpl();

        List<String> results = new ArrayList<>(xslNames.size());
        for (String xslName : xslNames) {
            try (InputStream xsl = openStylesheet(xslName)) {
                Transformer transformer = factory.newTransformer(new StreamSource(xsl));
                StringWriter out = new StringWriter();
                transformer.transform(new StreamSource(odmXml), new StreamResult(out));
                results.add(out.toString());
            }
        }
        LOG.info("XSLT extract: applied {} stylesheet(s) to a {}-byte ODM document",
                xslNames.size(), odmXml.length());
        return results;
    }

    /**
     * Opens a stylesheet, preferring the administrator-editable copy under
     * {@code ${filePath}/xslt} over the packaged one.
     */
    private static InputStream openStylesheet(String xslName) throws IOException {
        if (xslName == null || xslName.isBlank()) {
            throw new IOException("blank stylesheet name");
        }
        // Reject anything that could escape the stylesheet directory: these
        // names come from extract.properties, which an administrator edits.
        if (xslName.contains("/") || xslName.contains("\\") || xslName.contains("..")) {
            throw new IOException("illegal stylesheet name: " + xslName);
        }

        String base = null;
        try {
            base = CoreResources.getField("filePath");
        } catch (Exception ignored) {
            // No application context (unit test) — fall through to classpath.
        }
        if (base != null && !base.isBlank()) {
            if (!base.endsWith(File.separator)) base = base + File.separator;
            File onDisk = new File(base + XSLT_SUBDIR, xslName);
            if (onDisk.isFile()) {
                return new java.io.FileInputStream(onDisk);
            }
        }

        InputStream packaged = OdmXsltTransformer.class.getClassLoader()
                .getResourceAsStream(CLASSPATH_PREFIX + xslName);
        if (packaged == null) {
            throw new IOException("stylesheet not found on disk or classpath: " + xslName);
        }
        return packaged;
    }
}
