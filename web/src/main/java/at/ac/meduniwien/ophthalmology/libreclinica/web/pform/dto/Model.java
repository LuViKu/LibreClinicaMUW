/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.pform.dto;

import java.util.List;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

@XmlAccessorType(XmlAccessType.NONE)
public class Model {
    @XmlElement(name = "bind")
    private List<Bind> bind;
    @XmlElement(name = "instance")
    private String instance = "initialvalueinmodeldto";

    public String getInstance() {
        return instance;
    }
    public void setInstance(String instance) {
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
