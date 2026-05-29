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

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "html", namespace = "http://www.w3.org/1999/xhtml")
@XmlAccessorType(XmlAccessType.NONE)
public class Html {
    @XmlElement(name = "head", namespace = "http://www.w3.org/1999/xhtml")
    private Head head;
    @XmlElement(name = "body", namespace = "http://www.w3.org/1999/xhtml")
    private Body body;

    public Html() {}

    public Html(Html html) {
        setHead(html.getHead());
        setBody(html.getBody());
    }

    public Head getHead() {
        return head;
    }
    public void setHead(Head head) {
        this.head = head;
    }
    public Body getBody() {
        return body;
    }
    public void setBody(Body body) {
        this.body = body;
    }
}
