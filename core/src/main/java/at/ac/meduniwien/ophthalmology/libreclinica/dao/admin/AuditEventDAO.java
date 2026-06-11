/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.AuditEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.TriggerBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.SubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.AuditableEntityDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.DAODigester;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.SQLFactory;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.TypeNames;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.SubjectDAO;

/**
 * @author jxu, thickerson
 * 
 * 
 */
public class AuditEventDAO extends AuditableEntityDAO<AuditEventBean> {
    // private DAODigester digester;

    public AuditEventDAO(DataSource ds) {
        super(ds);
    }

    public AuditEventDAO(DataSource ds, DAODigester digester) {
        super(ds);
        this.digester = digester;
    }

    @Override
    protected void setDigesterName() {
        digesterName = SQLFactory.getInstance().DAO_AUDITEVENT;
    }

    @Override
    public void setTypesExpected() {
        // NUMERIC DATE VARCHAR(500) NUMERIC NUMERIC VARCHAR(1000) VARCHAR(4000)
        this.unsetTypeExpected();
        this.setTypeExpected(1, TypeNames.INT);
        this.setTypeExpected(2, TypeNames.TIMESTAMP);
        this.setTypeExpected(3, TypeNames.STRING);
        this.setTypeExpected(4, TypeNames.INT);
        this.setTypeExpected(5, TypeNames.INT);
        this.setTypeExpected(6, TypeNames.STRING);
        this.setTypeExpected(7, TypeNames.STRING);

    }

    /**
     * <p>
     * getEntityFromHashMap, the method that gets the object from the database
     * query.
     */
    public AuditEventBean getEntityFromHashMap(HashMap<String, Object> hm) {
        AuditEventBean eb = new AuditEventBean();
        // AUDIT_ID AUDIT_DATE AUDIT_TABLE USER_ID ENTITY_ID
        // REASON_FOR_CHANGE
        eb.setId(((Integer) hm.get("audit_id")).intValue());
        eb.setAuditDate((java.util.Date) hm.get("audit_date"));
        // eb.setAuditDate(new
        // Date(((java.sql.Timestamp)hm.get("audit_date")).getTime()));
        eb.setAuditTable((String) hm.get("audit_table"));
        eb.setUserId(((Integer) hm.get("user_id")).intValue());
        eb.setEntityId(((Integer) hm.get("entity_id")).intValue());
        eb.setReasonForChange(((String) hm.get("reason_for_change")).trim());
        eb.setActionMessage(((String) hm.get("action_message")).trim());

        return eb;
    }

    /**
     * <p>
     * getEntityFromHashMap, the method that gets the object from the database
     * query.
     */
    public AuditEventBean getEntityFromHashMap(HashMap<String, Object> hm, boolean hasValue, boolean hasColumnName, boolean hasContextIds) {
        AuditEventBean eb = new AuditEventBean();
        // AUDIT_ID AUDIT_DATE AUDIT_TABLE USER_ID ENTITY_ID
        // REASON_FOR_CHANGE ACTION_MESSAGE
        eb.setId(((Integer) hm.get("audit_id")).intValue());
        eb.setAuditDate((java.util.Date) hm.get("audit_date"));
        // used as a test, ignore and remove, tbh
        // java.text.SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy
        // hh:mm:ss");
        // logger.warn("** timestamp found: "+sdf.format(eb.getAuditDate()));
        // eb.setAuditDate(new
        // Date(((java.sql.Timestamp)hm.get("audit_date")).getTime()));
        eb.setAuditTable((String) hm.get("audit_table"));
        eb.setUserId(((Integer) hm.get("user_id")).intValue());
        eb.setEntityId(((Integer) hm.get("entity_id")).intValue());
        eb.setReasonForChange((String) hm.get("reason_for_change"));
        eb.setActionMessage((String) hm.get("action_message"));
        if (hasValue) {
            // logger.warn("*** has value");
            //
            eb.setOldValue((String) hm.get("old_value"));
            eb.setNewValue((String) hm.get("new_value"));
        }
        if (hasColumnName) {
            // logger.warn("*** has value");
            eb.setColumnName((String) hm.get("column_name"));
        }
        if (hasContextIds) {
            // logger.warn("*** has context ids");
            eb.setStudyId(((Integer) hm.get("study_id")).intValue());
            eb.setSubjectId(((Integer) hm.get("subject_id")).intValue());
            // logger.warn("*** set context ids: " +
            // eb.getStudyId() + " " +
            // eb.getSubjectId());
        }

        return eb;
    }

    public AuditEventBean setStudyAndSubjectInfo(AuditEventBean aeb) {
        if (aeb.getStudyId() > 0) {
            StudyDAO sdao = new StudyDAO(this.ds);
            StudyBean sbean = (StudyBean) sdao.findByPK(aeb.getStudyId());
            aeb.setStudyName(sbean.getName());
        }
        if (aeb.getSubjectId() > 0) {
            SubjectBean subbean = new SubjectBean();
            SubjectDAO subdao = new SubjectDAO(this.ds);
            subbean = (SubjectBean) subdao.findByPK(aeb.getSubjectId());
            aeb.setSubjectName(subbean.getName());
        }
        if (aeb.getUserId() > 0) {
            UserAccountBean updater = new UserAccountBean();
            UserAccountDAO uadao = new UserAccountDAO(this.ds);
            updater = (UserAccountBean) uadao.findByPK(aeb.getUserId());
            aeb.setUpdater(updater);
        }
        return aeb;
    }

    /**
     * getFinalEntityFromCollection, code created to place many of the functions
     * originally in findAllByUser here, so that it can be used efficiently by
     * findEventStatusLogByStudySubject.
     * 
     * @return an AuditEventBean, containing all the preset values
     */
    // public AuditEventBean getFinalEntityFromCollection() {
    // AuditEventBean eb = new AuditEventBean();
    //
    // return eb;
    // }
    /**
     * <p>
     * getEntityFromHashMap, the method that gets the object from the database
     * query.
     */
    public AuditEventBean getColumnNameFromHashMap(HashMap<String, Object> hm) {
        AuditEventBean eb = new AuditEventBean();

        eb.setColumnName((String) hm.get("column_name"));
        return eb;
    }

    public ArrayList<AuditEventBean> findAll() {
    	String queryName = "findAll";
    	return executeFindAllQuery(queryName);
    }

    /**
     * NOT IMPLEMENTED
     */
    public ArrayList<AuditEventBean>  findAll(String strOrderByColumn, boolean blnAscendingSort, String strSearchPhrase) {
    	throw new RuntimeException("Not implemented");
    }

    public AuditEventBean findByPK(int id) {
    	String queryName = "findByPK";
    	return (AuditEventBean) executeFindByPKQuery(queryName);
    }

    /**
     * @deprecated Phase audit-unification (2026-06-12) — DO NOT CALL.
     *     Writes to the legacy {@code audit_event} table, which is
     *     INVISIBLE to the SPA Audit Log view (only {@code
     *     audit_log_event} is read). All in-tree callers have been
     *     migrated to direct INSERTs into {@code audit_log_event}
     *     using the {@link at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AuditTypeIds}
     *     constants. The method is kept as a no-op-equivalent for one
     *     release cycle as a safety net for any out-of-tree caller;
     *     it will be physically removed in the next quarterly
     *     cleanup PR. See
     *     {@code docs/development/audit-coverage-2026-06-11.md}.
     *
     *     The Javadoc claim that this writes to {@code audit_log_event}
     *     is the original aspiration ("needs to change, tbh 02/2009"
     *     inline comment); the actual SQL writes to {@code audit_event}.
     *     This historical inconsistency is preserved as evidence in
     *     the audit-coverage doc.
     */
    @Deprecated
    public AuditEventBean create(AuditEventBean sb) {
        HashMap<Integer, Object> variables = new HashMap<Integer, Object>();
        // INSERT INTO audit_event
        // (AUDIT_DATE,AUDIT_TABLE,USER_ID,ENTITY_ID,REASON_FOR_CHANGE,
        // ACTION_MESSAGE)
        // VALUES (NOW(),?,?,?,?,?)
        // needs to change, tbh 02/2009
        // new query needs to be
        // INSERT INTO audit_log_event(audit_id, audit_log_event_type_id,
        // audit_date, user_id, audit_table, entity_id, entity_name, old_value,
        // new_value)
        // VALUES (pk, ?, now(), NEW.update_id, ?, ?, ?, ?, ?);
        variables.put(new Integer(1), sb.getAuditTable());
        variables.put(new Integer(2), new Integer(sb.getUserId()));
        variables.put(new Integer(3), new Integer(sb.getEntityId()));
        variables.put(new Integer(4), sb.getReasonForChangeKey());
        variables.put(new Integer(5), sb.getActionMessageKey());

        this.executeUpdate(digester.getQuery("create"), variables);

        return sb;
    }

    /**
     * Phase audit-unification (2026-06-12) — job conclusion writer.
     *
     * <p>Direct INSERT into {@code audit_log_event} using the
     * caller-supplied {@code eventTypeId}. Replaces a legacy path that
     * went through {@link #create(AuditEventBean)} (legacy
     * {@code audit_event}, invisible to the SPA Audit Log view).
     *
     * <p>{@code audit_table='dataset'} with {@code entity_id} set to the
     * trigger's dataset id so the Audit Log view groups the row with
     * other dataset lifecycle entries.
     */
    public void createRowForJobConclusion(TriggerBean trigger, int eventTypeId) {
        int userId = trigger.getUserAccount() == null ? 0 : trigger.getUserAccount().getId();
        int entityId = trigger.getDataset() == null ? 0 : trigger.getDataset().getId();
        String entityName = trigger.getFullName() == null ? "" : trigger.getFullName();
        try (Connection c = this.ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'dataset', ?, ?, '', '')")) {
            ps.setInt(1, eventTypeId); // caller-supplied AuditTypeIds.*
            ps.setInt(2, userId);
            ps.setInt(3, entityId);
            ps.setString(4, entityName);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Audit write failed for job conclusion trigger={} type={} (continuing): {}",
                    entityName, eventTypeId, e.getMessage());
        }
    }

    /**
     * Phase audit-unification (2026-06-12) — user-account audit writer.
     *
     * <p>Direct INSERT into {@code audit_log_event} with
     * {@code audit_log_event_type_id=105}
     * ({@code AuditTypeIds.USER_ACCOUNT_GENERIC}). Replaces a legacy
     * path that went through {@link #create(AuditEventBean)} (legacy
     * {@code audit_event}, invisible to the SPA Audit Log view).
     *
     * <p>{@code reasonForChange} + {@code actionMessage} are packed into
     * {@code new_value} as a pipe-separated pair so the Audit Log view
     * can surface both fields without an extra column. {@code audit_table}
     * is the literal {@code user_account} (the legacy quirk of storing
     * the i18n key {@code __user_account} is dropped — direct writers
     * across the audit-unification sweep all use the literal table name).
     */
    public void createRowForUserAccount(UserAccountBean uab, String reasonForChange, String actionMessage) {
        String reason = reasonForChange == null ? "" : reasonForChange;
        String action = actionMessage == null ? "" : actionMessage;
        try (Connection c = this.ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value, reason_for_change) "
                             + "VALUES (?, now(), ?, 'user_account', ?, ?, '', ?, ?)")) {
            ps.setInt(1, 105); // AuditTypeIds.USER_ACCOUNT_GENERIC
            ps.setInt(2, uab.getId());
            ps.setInt(3, uab.getId());
            ps.setString(4, uab.getName() == null ? "" : uab.getName());
            ps.setString(5, action);
            ps.setString(6, reason);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Audit write failed for user_account user={} action={} (continuing): {}",
                    uab.getId(), action, e.getMessage());
        }
    }

    /**
     * Phase audit-unification (2026-06-12) — failed-login audit writer.
     *
     * <p>Direct INSERT into {@code audit_log_event} with
     * {@code audit_log_event_type_id=101}
     * ({@code AuditTypeIds.USER_LOGIN_FAILED}). The type is hidden
     * ({@code is_user_visible=false}) — surfaces only in the sysadmin
     * {@code /api/v1/audit/system} view.
     *
     * <p>Drives the production failed-login filter
     * ({@code OpenClinicaAuthenticationProcessingFilter.unsuccessfulAuthentication}).
     */
    public void createRowForFailedLogin(UserAccountBean uab) {
        try (Connection c = this.ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'user_account', ?, ?, '', '')")) {
            ps.setInt(1, 101); // AuditTypeIds.USER_LOGIN_FAILED
            ps.setInt(2, uab.getId());
            ps.setInt(3, uab.getId());
            ps.setString(4, uab.getName() == null ? "" : uab.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Audit write failed for failed-login user={} (continuing): {}",
                    uab.getId(), e.getMessage());
        }
    }

    /**
     * Phase audit-unification (2026-06-12) — successful-login audit writer.
     *
     * <p>Direct INSERT into {@code audit_log_event} with
     * {@code audit_log_event_type_id=102}
     * ({@code AuditTypeIds.USER_LOGGED_IN}).
     */
    public void createRowForLogin(UserAccountBean uab) {
        try (Connection c = this.ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'user_account', ?, ?, '', '')")) {
            ps.setInt(1, 102); // AuditTypeIds.USER_LOGGED_IN
            ps.setInt(2, uab.getId());
            ps.setInt(3, uab.getId());
            ps.setString(4, uab.getName() == null ? "" : uab.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Audit write failed for login user={} (continuing): {}",
                    uab.getId(), e.getMessage());
        }
    }

    /**
     * Phase audit-unification (2026-06-12) — legacy password-request
     * audit writer.
     *
     * <p>Direct INSERT into {@code audit_log_event} with
     * {@code audit_log_event_type_id=103}
     * ({@code AuditTypeIds.USER_PASSWORD_REQUEST_LEGACY}). The legacy
     * "request password" workflow has no callers in the current SPA
     * surface; the helper is retained for the legacy servlet path that
     * may still resolve it via reflection / Spring wiring.
     */
    public void createRowForPasswordRequest(UserAccountBean uab) {
        try (Connection c = this.ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'user_account', ?, ?, '', '')")) {
            ps.setInt(1, 103); // AuditTypeIds.USER_PASSWORD_REQUEST_LEGACY
            ps.setInt(2, uab.getId());
            ps.setInt(3, uab.getId());
            ps.setString(4, uab.getName() == null ? "" : uab.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Audit write failed for password-request user={} (continuing): {}",
                    uab.getId(), e.getMessage());
        }
    }

    /**
     * Phase audit-unification (2026-06-12) — extract-job execution
     * audit writer (shared body for success + failure overloads).
     *
     * <p>Direct INSERT into {@code audit_log_event} with the
     * caller-supplied {@code eventTypeId} (106 for success, 107 for
     * failure). {@code audit_table='dataset'} with {@code entity_id}
     * set to the trigger's dataset id so the Audit Log view groups the
     * row with other dataset lifecycle entries. The free-text
     * {@code actionMessage} (job output excerpt) is preserved in
     * {@code new_value}.
     */
    private void writeJobExecutionAudit(TriggerBean triggerBean, int eventTypeId,
                                        String reasonForChange, String actionMessage) {
        int userId = triggerBean.getUserAccount() == null ? 0 : triggerBean.getUserAccount().getId();
        int entityId = triggerBean.getDataset() == null ? 0 : triggerBean.getDataset().getId();
        String entityName = triggerBean.getFullName() == null ? "" : triggerBean.getFullName();
        String reason = reasonForChange == null ? "" : reasonForChange;
        String action = actionMessage == null ? "" : actionMessage;
        try (Connection c = this.ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value, reason_for_change) "
                             + "VALUES (?, now(), ?, 'dataset', ?, ?, '', ?, ?)")) {
            ps.setInt(1, eventTypeId); // caller-supplied AuditTypeIds.EXTRACT_JOB_*
            ps.setInt(2, userId);
            ps.setInt(3, entityId);
            ps.setString(4, entityName);
            ps.setString(5, action);
            ps.setString(6, reason);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Audit write failed for job execution trigger={} type={} (continuing): {}",
                    entityName, eventTypeId, e.getMessage());
        }
    }

    /**
     * Phase audit-unification (2026-06-12) — generic job-execution
     * audit writer kept for the legacy callers that don't know the
     * specific success/failure outcome. Defaults to
     * {@code AuditTypeIds.EXTRACT_JOB_SUCCEEDED} (106) since the no-op
     * "no failure observed" case is the conservative interpretation.
     */
    public void createRowForJobExecution(TriggerBean triggerBean, String reasonForChange, String actionMessage) {
        writeJobExecutionAudit(triggerBean, 106, reasonForChange, actionMessage);
    }

    public void createRowForExtractDataJobSuccess(TriggerBean triggerBean) {
        writeJobExecutionAudit(triggerBean, 106, // AuditTypeIds.EXTRACT_JOB_SUCCEEDED
                "__job_fired_success", "__job_fired_success");
    }

    public void createRowForExtractDataJobSuccess(TriggerBean triggerBean, String message) {
        writeJobExecutionAudit(triggerBean, 106, // AuditTypeIds.EXTRACT_JOB_SUCCEEDED
                "__job_fired_success", message);
    }

    public void createRowForExtractDataJobFailure(TriggerBean triggerBean) {
        writeJobExecutionAudit(triggerBean, 107, // AuditTypeIds.EXTRACT_JOB_FAILED
                "__job_fired_fail", "__job_fired_fail");
    }

    public void createRowForExtractDataJobFailure(TriggerBean triggerBean, String message) {
        writeJobExecutionAudit(triggerBean, 107, // AuditTypeIds.EXTRACT_JOB_FAILED
                "__job_fired_fail", message);
    }

    public ArrayList<AuditEventBean> findAllByAuditTable(String tableName) {
        HashMap<Integer, Object> variables = variables(tableName);
        String queryName = "findAllByAuditTable";
        return executeFindAllQuery(queryName, variables);
    }

    public ArrayList<AuditEventBean> findAggregatesByTableName(String tableName) {
        this.unsetTypeExpected();
        this.setTypeExpected(1, TypeNames.LONG);
        this.setTypeExpected(2, TypeNames.STRING);
        HashMap<Integer, Object> variables = new HashMap<>();
        variables.put(new Integer(1), tableName);

        String sql = digester.getQuery("findAggregatesByTableName");
        logger.debug("sql is: " + sql);
        ArrayList<HashMap<String, Object>> alist = this.select(sql, variables);
        logger.debug("size is: " + alist.size());

        ArrayList<AuditEventBean> al = new ArrayList<>();

        for (HashMap<String, Object> map : alist) {
			logger.debug("has next..");
            AuditEventBean eb = this.getColumnNameFromHashMap(map);
            logger.debug("got bean");
            al.add(eb);
        }

        return al;

    }
    
    public ArrayList<AuditEventBean> findAllByStudyId(int studyId) {
        /*
         * select ae. , aev.old_value, aev.new_value, aev.column_name,
         * aec.study_id, aec.subject_id from audit_event ae, audit_event_values
         * aev, audit_event_context aec where ae.audit_id=aev.audit_id and
         * ae.audit_id = aec.audit_id and aec.study_id=? order by ae.audit_date
         */

        ArrayList<AuditEventBean> al = this.findAllByEntityName(studyId, "findAllByStudyId");

        return al;
    }

    public ArrayList<AuditEventBean> findAllByStudyIdAndLimit(int studyId) {
        /*
         * select ae. , aev.old_value, aev.new_value, aev.column_name,
         * aec.study_id, aec.subject_id from audit_event ae, audit_event_values
         * aev, audit_event_context aec where ae.audit_id=aev.audit_id and
         * ae.audit_id = aec.audit_id and aec.study_id=? order by ae.audit_date
         */

        ArrayList<AuditEventBean> al = this.findAllByEntityName(studyId, "findAllByStudyIdAndLimit");

        return al;
    }

    public ArrayList<AuditEventBean> findAllByEntityName(int entityId, String digesterName) {

        this.unsetTypeExpected();

        this.setTypeExpected(1, TypeNames.INT);
        this.setTypeExpected(2, TypeNames.TIMESTAMP);
        this.setTypeExpected(3, TypeNames.STRING);
        this.setTypeExpected(4, TypeNames.INT);
        this.setTypeExpected(5, TypeNames.INT);
        this.setTypeExpected(6, TypeNames.STRING);
        this.setTypeExpected(7, TypeNames.STRING); // action_message
        this.setTypeExpected(8, TypeNames.STRING); // old_value
        this.setTypeExpected(9, TypeNames.STRING); // new_value
        this.setTypeExpected(10, TypeNames.STRING); // column_name
        this.setTypeExpected(11, TypeNames.INT); // study_id
        this.setTypeExpected(12, TypeNames.INT); // subject_id
        
        HashMap<Integer, Object> variables = variables(entityId);

        String sql = digester.getQuery(digesterName);
        ArrayList<HashMap<String, Object>> alist = this.select(sql, variables);
        ArrayList<AuditEventBean> al = new ArrayList<>();
        AuditEventBean ebCheck = new AuditEventBean();
        HashMap<Integer, AuditEventBean> AuditEventHashMap = new HashMap<>();
        AuditEventBean eb = new AuditEventBean();
        for (HashMap<String, Object> nextEb : alist) {
            eb = this.getEntityFromHashMap(nextEb, true, true, true);

            ebCheck = AuditEventHashMap.get(eb.getId());
            if (ebCheck == null) {
                AuditEventHashMap.put(eb.getId(), eb);
                al.add(eb);
            } else {
            	// update changes
                HashMap<String, String> changes = ebCheck.getChanges();
                changes.put(eb.getColumnName(), eb.getNewValue());
            }

        }// end of first iterator loop        
        for (AuditEventBean newAEBean : al) {
            newAEBean = this.setStudyAndSubjectInfo(newAEBean);
        }// end of second iterator loop

        return al;
    }

    public ArrayList<AuditEventBean> findAllByUserId(int userId) {
        /*
         * select ae. , aev.old_value, aev.new_value, aev.column_name from
         * audit_event ae, audit_event_values aev where ae.audit_id=aev.audit_id
         * and ae.user_id=? order by ae.audit_date; NEWER QUERY : select ae. ,
         * aev.old_value, aev.new_value, aev.column_name, aec.study_id,
         * aec.subject_id from audit_event ae, audit_event_values aev,
         * audit_event_context aec where ae.audit_id=aev.audit_id and
         * ae.audit_id = aec.audit_id and ae.user_id=? order by ae.audit_date
         */
        this.unsetTypeExpected();

        this.setTypeExpected(1, TypeNames.INT);
        this.setTypeExpected(2, TypeNames.TIMESTAMP);
        this.setTypeExpected(3, TypeNames.STRING);
        this.setTypeExpected(4, TypeNames.INT);
        this.setTypeExpected(5, TypeNames.INT);
        this.setTypeExpected(6, TypeNames.STRING);
        this.setTypeExpected(7, TypeNames.STRING); // action_message
        this.setTypeExpected(8, TypeNames.STRING); // old_value
        this.setTypeExpected(9, TypeNames.STRING); // new_value
        this.setTypeExpected(10, TypeNames.STRING); // column_name
        this.setTypeExpected(11, TypeNames.INT); // study_id
        this.setTypeExpected(12, TypeNames.INT); // subject_id
        HashMap<Integer, Object> variables = variables(userId);

        String sql = digester.getQuery("findAllByUserId");
        ArrayList<HashMap<String, Object>> alist = this.select(sql, variables);
        ArrayList<AuditEventBean> al = new ArrayList<>();
        AuditEventBean ebCheck = new AuditEventBean();
        HashMap<Integer, AuditEventBean> AuditEventHashMap = new HashMap<>();
        AuditEventBean eb = new AuditEventBean();
        for (HashMap<String, Object> nextEb : alist) {
            eb = this.getEntityFromHashMap(nextEb, true, true, true);
            // currently added here, but is there repeated work?
            // create a method instead to just add the names to the ids
            // found in the context, tbh
            ebCheck = AuditEventHashMap.get(eb.getId());
            if (ebCheck == null) {
                AuditEventHashMap.put(new Integer(eb.getId()), eb);
                logger.warn("Put into hashmap: " + eb.getId());
                al.add(eb);
            } else {
                HashMap<String, String> changes = ebCheck.getChanges();
                changes.put(eb.getColumnName(), eb.getNewValue());
            }
        }// end of first iterator loop
        for(AuditEventBean newAEBean: al) {
            newAEBean = this.setStudyAndSubjectInfo(newAEBean);

        }// end of second iterator loop
        // add check for the context here, add in study name and subject name
        // if necessary

        return al;
    }

    public ArrayList<AuditEventBean> findEventStatusLogByStudySubject(int studySubjectId) {

        /*
         * select ae. , aev.old_value, aev.new_value from audit_event ae,
         * audit_event_values aev, study_event se where
         * ae.audit_table='STUDY_EVENT' and ae.audit_id=aev.audit_id and
         * aev.column_name='Subject Event Status ID' and
         * ae.entity_id=study_event.study_event_id and
         * study_event.study_subject_id=?
         */
        this.unsetTypeExpected();

        this.setTypeExpected(1, TypeNames.INT);
        this.setTypeExpected(2, TypeNames.TIMESTAMP);
        this.setTypeExpected(3, TypeNames.STRING);
        this.setTypeExpected(4, TypeNames.INT);
        this.setTypeExpected(5, TypeNames.INT);
        this.setTypeExpected(6, TypeNames.STRING);
        this.setTypeExpected(7, TypeNames.STRING); // action_message
        this.setTypeExpected(8, TypeNames.STRING); // old_value
        this.setTypeExpected(9, TypeNames.STRING); // new_value
        this.setTypeExpected(10, TypeNames.STRING); // column name
        HashMap<Integer, Object> variables = variables(studySubjectId);
        logger.debug("&&& querying study log...");
        String query = digester.getQuery("findEventStatusLogByStudySubject");
        
        ArrayList<HashMap<String, Object>> alist = this.select(query, variables);
        ArrayList<AuditEventBean> al = new ArrayList<>();
        logger.debug("&&& about to get entities from HM...");
        for (HashMap<String, Object> nextEb : alist) {
            AuditEventBean eb = this.getEntityFromHashMap(nextEb, true, true, false);
            al.add(eb);
        }
        logger.debug("&&& returning array list...");
        return al;

    }

    /**
     * Updates a AuditEvent
     */
    public AuditEventBean update(AuditEventBean eb) {
        return eb;
    }

    /**
     * NOT IMPLEMENTED
     */
    public ArrayList<AuditEventBean> findAllByPermission(Object objCurrentUser, int intActionType, String strOrderByColumn, boolean blnAscendingSort, String strSearchPhrase) {
       throw new RuntimeException("Not implemented");
    }

    /**
     * NOT IMPLEMENTED
     */
    public ArrayList<AuditEventBean> findAllByPermission(Object objCurrentUser, int intActionType) {
    	throw new RuntimeException("Not implemented");
    }

	@Override
	public AuditEventBean emptyBean() {
		return new AuditEventBean();
	}

    /**
     * Phase A1 (2026-06-10) — failure-audit write path.
     *
     * <p>Inserts an {@code audit_log_event} row with
     * {@code audit_log_event_type_id=61} (OPERATION_FAILED, seeded by
     * {@code lc-muw-2026-06-10-audit-event-type-operation-failed.xml})
     * recording that a wrapped controller / servlet method threw.
     *
     * <p>The row is written via a <strong>separate JDBC connection</strong>
     * obtained directly from the DataSource with {@code autoCommit=true}.
     * This is the manual equivalent of Spring's
     * {@code @Transactional(propagation = REQUIRES_NEW)} — the caller's
     * outer transaction can roll back its partial write while this row
     * stays committed, preserving the §11.10(e) audit trail entry.
     * Legacy callers use direct JDBC, not Spring tx managers, so a
     * propagation-based attribute would silently no-op; piggy-backing
     * on the caller's connection would tie the audit row to the rollback.
     *
     * <p>The {@code new_value} column pipe-encodes the diagnostic triple
     * {@code errorClass|errorMessage|reqId}. Audit Log SPA renderers
     * that target this type split on the pipe, matching the convention
     * established by {@code EyeCohortTransitionsApiController.emitTransitionAudit}.
     *
     * @param userId       acting user id (0 if not authenticated)
     * @param entityType   table or symbolic name (e.g. "study_subject",
     *                     "eye_cohort_transition"); becomes
     *                     {@code audit_table}
     * @param entityId     domain entity id when applicable; null sets
     *                     SQL NULL for async paths (SMTP failure with
     *                     no entity context)
     * @param operation    controller method or action label;
     *                     becomes {@code entity_name}
     * @param errorClass   throwable class FQN
     * @param errorMessage throwable message, truncated to fit when packed
     * @param reqId        request correlation id from MDC; null on
     *                     pre-A4 callers and async paths
     * @throws SQLException when the audit insert itself fails — the
     *                     FailureAuditTemplate catches and logs at
     *                     ERROR so the original exception still wins
     */
    public void insertOperationFailure(int userId,
                                       String entityType,
                                       Integer entityId,
                                       String operation,
                                       String errorClass,
                                       String errorMessage,
                                       String reqId) throws SQLException {
        // Pipe-encode the diagnostic triple. audit_log_event.new_value
        // is VARCHAR(4000) (per the legacy ddl); we cap each component
        // proportionally so a 6 KB stack trace can't blow past the
        // column width.
        String safeClass = errorClass == null ? "" : errorClass;
        String safeMessage = errorMessage == null ? "" : errorMessage;
        String safeReqId = reqId == null ? "" : reqId;
        String newValue = truncate(safeClass, 200) + "|"
                + truncate(safeMessage, 3600) + "|"
                + truncate(safeReqId, 64);

        String safeEntityType = entityType == null ? "" : entityType;
        String safeOperation = operation == null ? "" : operation;

        // REQUIRES_NEW-equivalent: open a fresh connection straight from
        // the pool, force autoCommit=true, never touch the caller's
        // Connection. Try-with-resources closes the connection deterministically
        // even if the INSERT throws.
        try (Connection c = this.ds.getConnection()) {
            boolean priorAutoCommit = c.getAutoCommit();
            if (!priorAutoCommit) {
                c.setAutoCommit(true);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                            + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                            + "VALUES (61, now(), ?, ?, ?, ?, '', ?)")) {
                ps.setInt(1, userId);
                ps.setString(2, truncate(safeEntityType, 255));
                if (entityId == null) {
                    ps.setNull(3, Types.INTEGER);
                } else {
                    ps.setInt(3, entityId);
                }
                ps.setString(4, truncate(safeOperation, 255));
                ps.setString(5, truncate(newValue, 4000));
                ps.executeUpdate();
            } finally {
                if (!priorAutoCommit) {
                    // Restore for any pool wrapper that recycles
                    // connections without resetting autoCommit.
                    try {
                        c.setAutoCommit(priorAutoCommit);
                    } catch (SQLException ignore) {
                        // Connection is about to be closed by
                        // try-with-resources; nothing to do.
                    }
                }
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
