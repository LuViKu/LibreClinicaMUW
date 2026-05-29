<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.format" var="resformat"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes" var="restext"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.page_messages" var="resmessages"/>


<jsp:include page="include/managestudy_top_pages.jsp"/>

<!-- move the alert message to the sidebar-->
<jsp:include page="include/sideAlert.jsp"/>
<!-- then instructions-->
<tr id="sidebar_Instructions_open">
    <td class="sidebar_tab">

        <a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="../images/sidebar_collapse.gif" border="0" align="right" hspace="10"></a>

        <b><fmt:message key="instructions" bundle="${restext}"/></b>

        <div class="sidebar_tab_content">

            <fmt:message key="cancel_data_export_info" bundle="${restext}"/>

        </div>

    </td>

</tr>
<tr id="sidebar_Instructions_closed" style="display: none">
    <td class="sidebar_tab">

        <a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="../images/sidebar_expand.gif" border="0" align="right" hspace="10"></a>

        <b><fmt:message key="instructions" bundle="${restext}"/></b>

    </td>
</tr>
<jsp:include page="include/sideInfo.jsp"/>
<%-- Phase B.4 jmesa PR 7c (cohort 5c): jmesa scripts removed — the
     table is rendered client-side by include/scheduledJobsTable.jsp. --%>

<h1><span class="title_manage">
<fmt:message key="currently_executing_data_export_jobs" bundle="${resword}"/>
</span></h1>


<script type="text/javascript">
    function prompt(formObj,theStudySubjectId){
        var bool = confirm(
                "<fmt:message key="uncheck_sdv" bundle="${resmessages}"/>");
        if(bool){
            formObj.action='${pageContext.request.contextPath}/pages/unSdvStudySubject';
            formObj.theStudySubjectId.value=theStudySubjectId;
            formObj.submit();
        }
    }
</script>
<div id="subjectSDV">
    <form name='scheduledJobsForm' action="${pageContext.request.contextPath}/pages/cancelScheduledJob" method="GET">
        <%-- These hidden inputs are populated by the per-row Cancel
             button via JS in the include fragment, then submitted to
             /pages/cancelScheduledJob (unchanged from the legacy
             jmesa-rendered form). --%>
        <input type="hidden" name="theJobName" value="0">
        <input type="hidden" name="theJobGroupName" value="0">
        <input type="hidden" name="theTriggerGroupName" value="0">
        <input type="hidden" name="theTriggerName" value="0">
        <input type="hidden" name="redirection" value="listCurrentScheduledJobs">

        <jsp:include page="include/scheduledJobsTable.jsp"/>
    </form>
</div>
<jsp:include page="include/footer.jsp"/>
</body>
</html>