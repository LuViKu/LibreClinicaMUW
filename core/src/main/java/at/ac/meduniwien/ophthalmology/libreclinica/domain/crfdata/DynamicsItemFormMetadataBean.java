/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.crfdata;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemFormMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.AbstractMutableDomainObject;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * DynamicsItemFormMetadataBean
 * @author thickerson
 *
 */
@Entity
@Table(name = "dyn_item_form_metadata")
@GenericGenerator(name = "id-generator", strategy = "native", parameters = { @Parameter(name = "sequence_name", value = "dyn_item_form_metadata_id_seq") })
public class DynamicsItemFormMetadataBean extends AbstractMutableDomainObject {

    /**
	 * 
	 */
	private static final long serialVersionUID = -6155427462453046495L;
	private Boolean showItem;
    private Integer eventCrfId;
    private Integer itemId;
    private Integer itemFormMetadataId;
    private Integer crfVersionId;
    private Integer itemDataId;
    private Integer passedDde;

    public DynamicsItemFormMetadataBean() {
        showItem = false;
        eventCrfId = 0;
        itemId = 0;
        itemFormMetadataId = 0;
        crfVersionId = 0;
        itemDataId = 0;
        passedDde = 0;
    }

    public DynamicsItemFormMetadataBean(ItemFormMetadataBean metadataBean, EventCRFBean eventCRFBean) {
        setItemId(metadataBean.getItemId());
        setItemFormMetadataId(metadataBean.getId());
        setCrfVersionId(metadataBean.getCrfVersionId());
        setEventCrfId(eventCRFBean.getId());
        setPassedDde(0);
    }
    
    public DynamicsItemFormMetadataBean(ItemFormMetadataBean metadataBean, EventCRFBean eventCRFBean, ItemDataBean itemData) {
        setItemId(metadataBean.getItemId());
        setItemFormMetadataId(metadataBean.getId());
        setCrfVersionId(metadataBean.getCrfVersionId());
        setEventCrfId(eventCRFBean.getId());
        setItemDataId(itemData.getId());
        setPassedDde(0);
    }

    public boolean isShowItem() {
        return showItem;
    }

    public void setShowItem(boolean showItem) {
        this.showItem = showItem;
    }

    public Integer getPassedDde() {
        return passedDde;
    }

    public void setPassedDde(Integer passedDde) {
        this.passedDde = passedDde;
    }

    public Integer getItemDataId() {
        return itemDataId;
    }

    public void setItemDataId(Integer itemDataId) {
        this.itemDataId = itemDataId;
    }

    public int getEventCrfId() {
        return eventCrfId;
    }

    public void setEventCrfId(int eventCrfId) {
        this.eventCrfId = eventCrfId;
    }

    public int getItemId() {
        return itemId;
    }

    public void setItemId(int itemId) {
        this.itemId = itemId;
    }

    public int getItemFormMetadataId() {
        return itemFormMetadataId;
    }

    public void setItemFormMetadataId(int itemFormMetadataId) {
        this.itemFormMetadataId = itemFormMetadataId;
    }

    public int getCrfVersionId() {
        return crfVersionId;
    }

    public void setCrfVersionId(int crfVersionId) {
        this.crfVersionId = crfVersionId;
    }
}
