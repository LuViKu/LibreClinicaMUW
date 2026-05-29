<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="org.akaza.openclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="org.akaza.openclinica.i18n.notes" var="restext"/>

<jsp:include page="../include/submit-header.jsp"/>
<!-- start of managestudy/findSubjects.jsp -->

<%-- Phase B.4 jmesa PR 4c: server-rendered jmesa blob replaced with
     the vanilla-JS findSubjectsTable include (see DR / cohort 2c).
     The legacy add-new-subject overlay still uses jQuery.blockUI from
     the now-orphaned includes/jmesa/ assets — we keep loading just the
     two scripts that overlay genuinely needs. --%>

<!-- move the alert message to the sidebar-->
<jsp:include page="../include/sideAlert.jsp"/>

<script type="text/JavaScript" language="JavaScript" src="includes/jmesa/jquery.min.js"></script>
<script type="text/javascript" language="JavaScript" src="includes/jmesa/jquery.blockUI.js"></script>

<script type="text/javascript">
    $.noConflict();         // to avoid conflicts with prototype.js
    jQuery(document).ready(function() {
        jQuery('#addSubject').click(function() {
            jQuery.blockUI({message: jQuery('#addSubjectForm'), css:{left: "300px", top:"10px", width:"", padding:"1em", cursor:"default"}});
        });
        jQuery('#cancel').click(function() {
            jQuery.unblockUI();
            return false;
        });
        <c:if test="${showOverlay}">
            jQuery.blockUI({ message: jQuery('#addSubjectForm'), css:{left: "300px", top:"10px", cursor:"default"}});
        </c:if>
    });
</script>

<!-- then instructions-->
<tr id="sidebar_Instructions_open" style="display: none">
    <td class="sidebar_tab">
        <a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="images/sidebar_collapse.gif" class="sidebar_collapse_expand"></a>
        <b><fmt:message key="instructions" bundle="${resword}"/></b>
        <div class="sidebar_tab_content"></div>
    </td>
</tr>

<tr id="sidebar_Instructions_closed" style="display: all">
    <td class="sidebar_tab">
        <a href="javascript:leftnavExpand('sidebar_Instructions_open'); leftnavExpand('sidebar_Instructions_closed');"><img src="images/sidebar_expand.gif" class="sidebar_collapse_expand"></a>
        <b><fmt:message key="instructions" bundle="${resword}"/></b>
    </td>
</tr>

<!-- include study/site info -->
<jsp:include page="../include/sideInfo.jsp"/>

<jsp:useBean scope='session' id='userBean' class='org.akaza.openclinica.bean.login.UserAccountBean'/>
<jsp:useBean scope='request' id='crf' class='org.akaza.openclinica.bean.admin.CRFBean'/>

<h1><span class="title_manage"><fmt:message key="view_subjects_in" bundle="${restext}"/> <c:out value="${study.name}"/></span></h1>

<div id="findSubjectsDiv">
    <jsp:include page="include/findSubjectsTable.jsp"/>
</div>

<!-- compose the overlay to add new subject, but don't show it-->
<div id="addSubjectForm" style="display:none;">
    <c:import url="../submit/addNewSubjectExpressNew.jsp"></c:import>
</div>

<br />

<jsp:include page="../include/footer.jsp"/>
