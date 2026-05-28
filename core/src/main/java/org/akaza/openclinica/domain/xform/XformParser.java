/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.domain.xform;

import javax.sql.DataSource;

import org.akaza.openclinica.dao.core.CoreResources;
import org.akaza.openclinica.domain.xform.dto.Html;
import org.akaza.openclinica.service.xml.OdmJaxbContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * XForm marshal / unmarshal helper. Phase B.3 PR 3c-2 migrated the XML
 * binding from Castor (driven by {@code xformMapping.xml} +
 * {@code openRosaXFormMapping.xml}) to {@code javax.xml.bind} 2.3.x JAXB
 * via the shared {@link OdmJaxbContext}. The class is retained as a
 * thin compatibility shim so the rest of the XForm pipeline
 * ({@code FormBeanParser}, {@code WidgetFactory}, etc.) does not need to
 * know about the JAXB context bean.
 */
public class XformParser {
    private DataSource dataSource = null;
    protected final Logger log = LoggerFactory.getLogger(XformParser.class);
    private CoreResources coreResources;
    private OdmJaxbContext odmJaxbContext;

    public String marshall(Html html) throws Exception {
        return getOdmJaxbContext().marshalXform(html);
    }

    public Html unMarshall(String xml) throws Exception {
        return getOdmJaxbContext().unmarshalXform(xml);
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public CoreResources getCoreResources() {
        return coreResources;
    }

    public void setCoreResources(CoreResources coreResources) {
        this.coreResources = coreResources;
    }

    public OdmJaxbContext getOdmJaxbContext() {
        if (this.odmJaxbContext == null) {
            this.odmJaxbContext = new OdmJaxbContext();
        }
        return this.odmJaxbContext;
    }

    public void setOdmJaxbContext(OdmJaxbContext odmJaxbContext) {
        this.odmJaxbContext = odmJaxbContext;
    }

}
