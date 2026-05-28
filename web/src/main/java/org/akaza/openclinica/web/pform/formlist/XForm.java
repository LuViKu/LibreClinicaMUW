/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.web.pform.formlist;

import java.security.NoSuchAlgorithmException;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;

import org.akaza.openclinica.bean.admin.CRFBean;
import org.akaza.openclinica.bean.submit.CRFVersionBean;

@XmlAccessorType(XmlAccessType.NONE)
public class XForm {
    @XmlElement(name = "formID")
    private String formID = null;
    @XmlElement(name = "name")
    private String name = null;
    @XmlElement(name = "majorMinorVersion")
    private String majorMinorVersion = null;
    @XmlElement(name = "version")
    private String version = null;
    @XmlElement(name = "hash")
    private String hash = null;
    @XmlElement(name = "downloadUrl")
    private String downloadURL = null;
    @XmlElement(name = "manifestUrl")
    private String manifestURL = null;

    public XForm() {

    }

    public XForm(CRFBean crf, CRFVersionBean version) throws NoSuchAlgorithmException {
        this.formID = version.getOid();
        this.name = crf.getName();
        this.majorMinorVersion = version.getName();
        this.version = version.getName();

    }

    public String getFormID() {
        return formID;
    }

    public void setFormID(String formID) {
        this.formID = formID;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMajorMinorVersion() {
        return majorMinorVersion;
    }

    public void setMajorMinorVersion(String majorMinorVersion) {
        this.majorMinorVersion = majorMinorVersion;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public String getDownloadURL() {
        return downloadURL;
    }

    public void setDownloadURL(String downloadURL) {
        this.downloadURL = downloadURL;
    }

    public String getManifestURL() {
        return manifestURL;
    }

    public void setManifestURL(String manifestURL) {
        this.manifestURL = manifestURL;
    }

}
