/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap;

import java.util.Date;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.DataMapDomainObject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

/**
 * C4 (2026-06-20) — hard cross-study patient identity.
 *
 * <p>Anchors the identity Wave 1B introduced as a soft
 * {@code study_subject.patient_uuid} link. Every {@code study_subject}
 * row carries an FK {@code patient_id} pointing at exactly one
 * {@code patient} row; multiple {@code study_subject} rows can point at
 * the same {@code patient} to represent the same human enrolled in more
 * than one study.
 *
 * <p>The {@code patient_uuid} stays unique on the table — it is the
 * stable identifier that crosses system boundaries (export to a
 * downstream system, cross-DB joins, audit-trail correlation) without
 * exposing the surrogate {@code patient_id}.
 *
 * <p>Schema seeded by
 * {@code lc-muw-2026-06-20-patient-table.xml}.
 */
@Entity
@Table(name = "patient")
@GenericGenerator(name = "patient-id-generator",
                  strategy = "native",
                  parameters = {
                      @Parameter(name = "sequence_name",
                                 value = "patient_patient_id_seq")
                  })
public class Patient extends DataMapDomainObject {

    private static final long serialVersionUID = 1L;

    private Integer id;
    private String patientUuid;
    private Date createdAt;
    private Integer createdBy;

    @Id
    @Column(name = "patient_id", unique = true, nullable = false)
    @GeneratedValue(generator = "patient-id-generator")
    @Override
    public Integer getId() {
        return id;
    }

    @Override
    public void setId(Integer id) {
        this.id = id;
    }

    @Column(name = "patient_uuid", nullable = false, unique = true, length = 36)
    public String getPatientUuid() {
        return patientUuid;
    }

    public void setPatientUuid(String patientUuid) {
        this.patientUuid = patientUuid;
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    @Temporal(TemporalType.TIMESTAMP)
    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    @Column(name = "created_by", nullable = false)
    public Integer getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Integer createdBy) {
        this.createdBy = createdBy;
    }
}
