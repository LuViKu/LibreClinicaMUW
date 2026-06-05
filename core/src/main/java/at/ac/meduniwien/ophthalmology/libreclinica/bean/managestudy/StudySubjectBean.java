/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.AuditableEntityBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.oid.OidGenerator;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.oid.StudySubjectOidGenerator;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectGroupMapBean;

import java.util.ArrayList;
import java.util.Date;

/**
 * @author jxu
 *
 */
public class StudySubjectBean extends AuditableEntityBean {
    /**
	 * 
	 */
	private static final long serialVersionUID = 8163352308386400707L;

	// STUDY_SUBJECT_ID, LABEL, SUBJECT_ID, STUDY_ID
    // STATUS_ID, DATE_CREATED, OWNER_ID,
    // DATE_UPDATED, UPDATE_ID,secondary_label
    private String label = "";

    private int subjectId;

    private int studyId;
    
    /**
     * @vbc 08/06/2008 NEW EXTRACT DATA IMPLEMENTATION 
     * - add dob_collected
     */
    private boolean isDobCollected;
    
    // private int studyGroupId;

    private Date enrollmentDate;

    private String secondaryLabel = "";

    private String uniqueIdentifier = "";// not in the table, for display
    // purpose

    private String studyName = "";// not in the table, for display purpose

    private char gender = 'm';// not in the table, for display purpose

    private Date dateOfBirth;// not in the db

    /**
     * An array of the groups this subject belongs to. Each element is a
     * StudyGroupMapBean object. Not in the database.
     */
    private ArrayList<SubjectGroupMapBean> studyGroupMaps;
    
    private Date eventStartDate;//not in DB, for adding subject from subject matrix
    
    /**
     * The OID, used for export and import of data.
     */
    private String oid;

    private OidGenerator oidGenerator = new StudySubjectOidGenerator();
    private String time_zone;

    /**
     * Phase E.6 Tier 1 — ophthalmology study-eye scope.
     *
     * <p>One of {@code "OD"} (right), {@code "OS"} (left), {@code "OU"}
     * (both), or {@code null} for non-ophth studies / pre-randomization.
     * Persisted in {@code study_subject.study_eye} (VARCHAR(3),
     * nullable, no DB-level CHECK — enforced in Java + SPA).
     */
    private String studyEye;

    /**
     * Phase E.6 Tier 1 — date of the eligibility screening visit.
     *
     * <p>Typically a few days before {@link #enrollmentDate}; may be
     * null for retrospective imports or non-MUW deployments that don't
     * run a separate screening visit. Persisted in
     * {@code study_subject.screening_date} (DATE, nullable).
     */
    private Date screeningDate;

	public StudySubjectBean() {
        studyGroupMaps = new ArrayList<>();
    }

	public String getOid() {
		return oid;
	}

	public void setOid(String oid) {
		this.oid = oid;
	}

	public OidGenerator getOidGenerator() {
		return oidGenerator;
	}

	public void setOidGenerator(OidGenerator oidGenerator) {
		this.oidGenerator = oidGenerator;
	}

    /**
     * @return Returns the uniqueIndentifier.
     */
    public String getUniqueIdentifier() {
        return uniqueIdentifier;
    }

    /**
     * @param uniqueIdentifier
     *            The uniqueIdentifier to set.
     */
    public void setUniqueIdentifier(String uniqueIdentifier) {
        this.uniqueIdentifier = uniqueIdentifier;
    }

    /**
     * @return Returns the studyName.
     */
    public String getStudyName() {
        return studyName;
    }

    /**
     * @param studyName
     *            The studyName to set.
     */
    public void setStudyName(String studyName) {
        this.studyName = studyName;
    }

    /**
     * @return Returns the gender.
     */
    public char getGender() {
        return gender;
    }

    /**
     * @param gender
     *            The gender to set.
     */
    public void setGender(char gender) {
        this.gender = gender;
    }

    /**
     * @return Returns the label.
     */
    public String getLabel() {
        return label;
    }

    /**
     * @param label
     *            The label to set.
     */
    public void setLabel(String label) {
        this.label = label;
    }

    /**
     * @return Returns the secondaryLabel.
     */
    public String getSecondaryLabel() {
        return secondaryLabel;
    }

    /**
     * @param secondaryLabel
     *            The secondaryLabel to set.
     */
    public void setSecondaryLabel(String secondaryLabel) {
        this.secondaryLabel = secondaryLabel;
    }

    /**
     * @return Returns the studyId.
     */
    public int getStudyId() {
        return studyId;
    }

    /**
     * @param studyId
     *            The studyId to set.
     */
    public void setStudyId(int studyId) {
        this.studyId = studyId;
    }

    /**
     * @return Returns the subjectId.
     */
    public int getSubjectId() {
        return subjectId;
    }

    /**
     * @param subjectId
     *            The subjectId to set.
     */
    public void setSubjectId(int subjectId) {
        this.subjectId = subjectId;
    }

    /**
     * @return Returns the enrollmentDate.
     */
    public Date getEnrollmentDate() {
        return enrollmentDate;
    }

    /**
     * @param enrollmentDate
     *            The enrollmentDate to set.
     */
    public void setEnrollmentDate(Date enrollmentDate) {
        this.enrollmentDate = enrollmentDate;
    }

    // disambiguate the meaning of "name" in this context
    @Override
    public String getName() {
        return getLabel();
    }

    @Override
    public void setName(String name) {
        setLabel(name);
    }

    /**
     * @return Returns the studyGroupMaps.
     */
    public ArrayList<SubjectGroupMapBean> getStudyGroupMaps() {
        return studyGroupMaps;
    }

    /**
     * @param studyGroupMaps
     *            The studyGroupMaps to set.
     */
    public void setStudyGroupMaps(ArrayList<SubjectGroupMapBean> studyGroupMaps) {
        this.studyGroupMaps = studyGroupMaps;
    }

    /**
     * @return Returns the dateOfBirth.
     */
    public Date getDateOfBirth() {
        return dateOfBirth;
    }

    /**
     * @param dateOfBirth
     *            The dateOfBirth to set.
     */
    public void setDateOfBirth(Date dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    /**
     * @return the eventStartDate
     */
    public Date getEventStartDate() {
        return eventStartDate;
    }

    /**
     * @param eventStartDate the eventStartDate to set
     */
    public void setEventStartDate(Date eventStartDate) {
        this.eventStartDate = eventStartDate;
    }

	/**
	 * @return the isDobCollected
	 */
	public boolean isDobCollected() {
		return isDobCollected;
	}

	/**
	 * @param isDobCollected the isDobCollected to set
	 */
	public void setDobCollected(boolean isDobCollected) {
		this.isDobCollected = isDobCollected;
	}

	public String getTime_zone() {
		return time_zone;
	}

	public void setTime_zone(String time_zone) {
		this.time_zone = time_zone;
	}

    /**
     * @return the ophthalmology study-eye scope ("OD" / "OS" / "OU" or
     *         {@code null}).
     */
    public String getStudyEye() {
        return studyEye;
    }

    /**
     * @param studyEye one of {@code "OD" / "OS" / "OU"} or {@code null}.
     *                 Caller is responsible for validation; this setter
     *                 does not enforce the enum (the
     *                 {@code SubjectsApiController} does).
     */
    public void setStudyEye(String studyEye) {
        this.studyEye = studyEye;
    }

    /**
     * @return the eligibility-screening date, or {@code null} if not
     *         recorded.
     */
    public Date getScreeningDate() {
        return screeningDate;
    }

    /**
     * @param screeningDate eligibility-screening date.
     */
    public void setScreeningDate(Date screeningDate) {
        this.screeningDate = screeningDate;
    }

}