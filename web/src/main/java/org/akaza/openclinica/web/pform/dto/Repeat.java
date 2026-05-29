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

import java.util.List;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElements;

@XmlAccessorType(XmlAccessType.NONE)
public class Repeat {
    @XmlAttribute(name = "nodeset")
    private String nodeset;
    @XmlElements({
            @XmlElement(name = "input",   type = Input.class),
            @XmlElement(name = "select",  type = Select.class),
            @XmlElement(name = "select1", type = Select1.class)
    })
    private List<UserControl> usercontrol;
    @XmlElement(name = "label")
    private Label label;
    private String count;
    @XmlAttribute(name = "count", namespace = "http://openrosa.org/javarosa")
    private String jrCount;
    @XmlAttribute(name = "noAddRemove", namespace = "http://openrosa.org/javarosa")
    private String jrNoAddRemove;
    @XmlAttribute(name = "appearance")
    private String appearance;
    @XmlAttribute(name = "ref")
    private String ref;

    public Repeat() {
        // NOOP
    }

    public String getJrNoAddRemove() {
        return jrNoAddRemove;
    }
    public void setJrNoAddRemove(String jrNoAddRemove) {
        this.jrNoAddRemove = jrNoAddRemove;
    }
    public String getJrCount() {
        return jrCount;
    }
    public void setJrCount(String jrCount) {
        this.jrCount = jrCount;
    }
    public String getCount() {
        return count;
    }
    public void setCount(String count) {
        this.count = count;
    }
    public Label getLabel() {
        return label;
    }
    public void setLabel(Label label) {
        this.label = label;
    }
    public List<UserControl> getUsercontrol() {
        return usercontrol;
    }
    public void setUsercontrol(List<UserControl> usercontrol) {
        this.usercontrol = usercontrol;
    }
    public String getNodeset() {
        return nodeset;
    }
    public void setNodeset(String nodeset) {
        this.nodeset = nodeset;
    }
    public String getAppearance() {
        return appearance;
    }
    public void setAppearance(String appearance) {
        this.appearance = appearance;
    }
    public String getRef() {
        return ref;
    }
    public void setRef(String ref) {
        this.ref = ref;
    }
}
