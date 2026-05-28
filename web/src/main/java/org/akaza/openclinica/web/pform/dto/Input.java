/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.web.pform.dto;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "input")
@XmlAccessorType(XmlAccessType.NONE)
public class Input implements UserControl {
    @XmlAttribute(name = "ref")
    private String ref;
    @XmlAttribute(name = "appearance")
    private String appearance;
    @XmlElement(name = "label")
    private Label label = null;
    @XmlElement(name = "hint")
    private Hint hint = null;

    public String getRef() {
        return ref;
    }
    public void setRef(String ref) {
        this.ref = ref;
    }
    public String getAppearance() {
        return appearance;
    }
    public void setAppearance(String appearance) {
        this.appearance = appearance;
    }
    public Label getLabel() {
        return label;
    }
    public void setLabel(Label label) {
        this.label = label;
    }
    public Hint getHint() {
        return hint;
    }
    public void setHint(Hint hint) {
        this.hint = hint;
    }
}
