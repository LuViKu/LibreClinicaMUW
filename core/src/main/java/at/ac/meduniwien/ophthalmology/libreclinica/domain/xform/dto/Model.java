/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.xform.dto;

import java.util.List;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

@XmlAccessorType(XmlAccessType.NONE)
public class Model {
    @XmlElement(name = "itext")
    private Itext itext;
    @XmlElement(name = "bind")
    private List<Bind> bind;
    @XmlElement(name = "instance")
    private List<String> instance;// = "initialvalueinmodeldto";

    public Itext getItext() {
        return itext;
    }

    public void setItext(Itext itext) {
        this.itext = itext;
    }

    public List<String> getInstance() {
        return instance;
    }

    public void setInstance(List<String> instance) {
        this.instance = instance;
    }

    public List<Bind> getBind() {
        return bind;
    }

    public void setBind(List<Bind> bind) {
        this.bind = bind;
    }

    public Bind getBindByNodeSet(String nodeSet) {
        if (bind != null) {
            for (int i = 0; i < bind.size(); i++) {
                if (bind.get(i).getNodeSet().equals(nodeSet)) {
                    return bind.get(i);
                }
            }
        }
        return null;
    }
}
