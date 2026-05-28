/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.web.pform.formlist;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "xforms")
@XmlAccessorType(XmlAccessType.NONE)
public class XFormList {
	@XmlElement(name = "xform")
	private List<XForm> xforms = null;

	public XFormList()
	{
		xforms = new ArrayList<XForm>();
	}

	public void add(XForm xform)
	{
		xforms.add(xform);
	}

	public List<XForm> getXForms() {
		return xforms;
	}

	public void setXForms(List<XForm> xforms) {
		this.xforms = xforms;
	}

}
