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

/**
 * Marker interface for polymorphic XForm form controls inside a
 * {@link Group}, {@link Repeat}, or {@link Section}. Castor used the
 * deprecated {@code auto-naming="deriveByClass"} attribute on the
 * container mappings to dispatch by Java class name → element name
 * (Input → {@code <input>}, Select → {@code <select>}, Select1 →
 * {@code <select1>}, Upload → {@code <upload>}). The JAXB equivalent
 * is a {@code @XmlElements} list on the container fields naming each
 * concrete subtype explicitly — see {@link Group#usercontrol},
 * {@link Repeat#usercontrol}, {@link Section#usercontrol}.
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

    public String getMediatype();

}
