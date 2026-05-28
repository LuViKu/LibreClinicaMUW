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

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;

@XmlAccessorType(XmlAccessType.NONE)
public class Section {
    @XmlElement(name = "label")
    private Label label;
    @XmlElement(name = "group")
    private List<Group> group;
    @XmlAttribute(name = "appearance")
    private String appearance;
    @XmlElements({
            @XmlElement(name = "input",   type = Input.class),
            @XmlElement(name = "select",  type = Select.class),
            @XmlElement(name = "select1", type = Select1.class)
    })
    private List<UserControl> usercontrol;

    public List<Group> getGroup() {
        return group;
    }
    public void setGroup(List<Group> group) {
        this.group = group;
    }
    public Label getLabel() {
        return label;
    }
    public void setLabel(Label label) {
        this.label = label;
    }
    public String getAppearance() {
        return appearance;
    }
    public void setAppearance(String appearance) {
        this.appearance = appearance;
    }
    public List<UserControl> getUsercontrol() {
        return usercontrol;
    }
    public void setUsercontrol(List<UserControl> usercontrol) {
        this.usercontrol = usercontrol;
    }
}
