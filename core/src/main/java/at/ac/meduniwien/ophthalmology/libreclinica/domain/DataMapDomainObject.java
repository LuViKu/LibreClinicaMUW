/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain;

import java.io.Serializable;

import jakarta.persistence.Transient;

@SuppressWarnings("all")

public class DataMapDomainObject implements MutableDomainObject,Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = -301662448289660793L;

	@Override
	public void setId(Integer id) {
		
	}

	@Override
	@Transient
	public Integer getVersion() {
		return null;
	}

	@Override
	public void setVersion(Integer version) {
		
	}

	@Override
	@Transient
	public Integer getId() {
		return null;
	}
	

}
