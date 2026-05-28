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

/**
 * Marker interface — see core's
 * {@link org.akaza.openclinica.domain.xform.dto.UserControl} for the
 * design notes on the JAXB {@code @XmlElements}-based polymorphism that
 * replaces Castor's {@code auto-naming="deriveByClass"}. PFORM only
 * uses Input + Select + Select1 (no Upload), so the container-side
 * {@code @XmlElements} lists are shorter here.
 */
public interface UserControl {
    public String getRef();
    public void setRef(String ref);
    public String getAppearance();
    public void setAppearance(String appearance);
    public Label getLabel();
    public void setLabel(Label label);
    public Hint getHint();
    public void setHint(Hint hint);
}
