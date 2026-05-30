/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.pform.widget;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemFormMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemGroupBean;
import at.ac.meduniwien.ophthalmology.libreclinica.web.pform.dto.Bind;
import at.ac.meduniwien.ophthalmology.libreclinica.web.pform.dto.Input;
import at.ac.meduniwien.ophthalmology.libreclinica.web.pform.dto.Label;
import at.ac.meduniwien.ophthalmology.libreclinica.web.pform.dto.UserControl;

public class SubHeaderWidget extends BaseWidget {
	private ItemBean item = null;
	private ItemFormMetadataBean itemMetaData = null;
	private ItemGroupBean itemGroup = null;
	private CRFVersionBean version = null;
	private String expression;

	public SubHeaderWidget(CRFVersionBean version, ItemBean item, ItemFormMetadataBean itemMetaData, ItemGroupBean itemGroup,
			String appearance, String expression) {
		this.item = item;
		this.itemMetaData = itemMetaData;
		this.itemGroup = itemGroup;
		this.version = version;
		this.expression = expression;
	}

	@Override
	public UserControl getUserControl() {
		Input input = new Input();
		Label label = new Label();
		label.setLabel(itemMetaData.getSubHeader());
		input.setLabel(label);
		input.setRef("/" + version.getOid() + "/" + itemGroup.getOid() + "/" + item.getOid() + ".SUBHEADER");
		return input;
	}

	@Override
	public Bind getBinding() {

		Bind binding = new Bind();
		binding.setNodeSet("/" + version.getOid() + "/" + itemGroup.getOid() + "/" + item.getOid() + ".SUBHEADER");
		binding.setType("string");
		binding.setReadOnly("true()");
		String relevant = null;
		relevant = expression;
		if (relevant != null)
			binding.setRelevant(relevant);

		return binding;
	}

}
