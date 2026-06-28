/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.extract;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;

/**
 * @author jxu
 *
 */
public class DisplayItemHeaderBean {
    private String itemHeaderName;
    private ItemBean item;

    /**
     * @return Returns the item.
     */
    public ItemBean getItem() {
        return item;
    }

    /**
     * @param item
     *            The item to set.
     */
    public void setItem(ItemBean item) {
        this.item = item;
    }

    /**
     * @return Returns the itemHeaderName.
     */
    public String getItemHeaderName() {
        return itemHeaderName;
    }

    /**
     * @param itemHeaderName
     *            The itemHeaderName to set.
     */
    public void setItemHeaderName(String itemHeaderName) {
        this.itemHeaderName = itemHeaderName;
    }
}
