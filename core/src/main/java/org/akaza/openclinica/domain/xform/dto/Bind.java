/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.domain.xform.dto;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;

@XmlAccessorType(XmlAccessType.NONE)
public class Bind {
    @XmlAttribute(name = "nodeset")
    private String nodeSet;
    @XmlAttribute(name = "type")
    private String type;
    @XmlAttribute(name = "readonly")
    private String readOnly;
    @XmlAttribute(name = "calculate")
    private String calculate;
    @XmlAttribute(name = "constraint")
    private String constraint;
    @XmlAttribute(name = "constraintMsg")
    private String constraintMsg;
    @XmlAttribute(name = "required")
    private String required;
    @XmlAttribute(name = "preload", namespace = "http://openrosa.org/javarosa")
    private String jrPreload;
    @XmlAttribute(name = "relevant")
    private String relevant;

    public String getRelevant() {
        return relevant;
    }

    public void setRelevant(String relevant) {
        this.relevant = relevant;
    }

    public String getNodeSet() {
        return nodeSet;
    }

    public void setNodeSet(String nodeSet) {
        this.nodeSet = nodeSet;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getReadOnly() {
        return readOnly;
    }

    public void setReadOnly(String readOnly) {
        this.readOnly = readOnly;
    }

    public String getCalculate() {
        return calculate;
    }

    public void setCalculate(String calculate) {
        this.calculate = calculate;
    }

    public String getConstraint() {
        return constraint;
    }

    public void setConstraint(String constraint) {
        this.constraint = constraint;
    }

    public String getConstraintMsg() {
        return constraintMsg;
    }

    public void setConstraintMsg(String constraintMsg) {
        this.constraintMsg = constraintMsg;
    }

    public String getRequired() {
        return required;
    }

    public void setRequired(String required) {
        this.required = required;
    }

    public String getJrPreload() {
        return jrPreload;
    }

    public void setJrPreload(String jrPreload) {
        this.jrPreload = jrPreload;
    }
}
