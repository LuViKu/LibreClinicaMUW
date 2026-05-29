<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="org.akaza.openclinica.i18n.notes" var="restext"/>
<fmt:setBundle basename="org.akaza.openclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="org.akaza.openclinica.i18n.workflow" var="resworkflow"/>
<fmt:setBundle basename="org.akaza.openclinica.i18n.format" var="resformat"/>

<link type="text/css" href="includes/jmesa/jmesa.css"  rel="stylesheet">
<link rel="stylesheet" href="includes/styles.css" type="text/css">
<%-- <link rel="stylesheet" href="includes/styles2.css" type="text/css">--%>
<%-- <link rel="stylesheet" href="includes/NewNavStyles.css" type="text/css" />--%>

<script type="text/JavaScript" language="JavaScript" src="includes/global_functions_javascript.js"></script>
<%-- <script type="text/JavaScript" language="JavaScript" src="includes/global_functions_javascript2.js"></script> --%>
<script type="text/JavaScript" language="JavaScript" src="includes/Tabs.js"></script>
<script type="text/JavaScript" language="JavaScript" src="includes/CalendarPopup.js"></script>
<!-- Added for the new Calender -->

<link rel="stylesheet" type="text/css" media="all" href="includes/new_cal/skins/aqua/theme.css" title="Aqua" />
<script type="text/javascript" src="includes/new_cal/calendar.js"></script>
<script type="text/javascript" src="includes/new_cal/lang/calendar-en.js"></script>
<script type="text/javascript" src="includes/new_cal/lang/<fmt:message key="jscalendar_language_file" bundle="${resformat}"/>"></script>
<script type="text/javascript" src="includes/new_cal/calendar-setup.js"></script>

<%-- Phase B.4 jmesa PR 7a (cohort 5a): jmesa scripts removed —
     table rendered client-side by ../include/viewRuleAssignmentTable.jsp. --%>

<jsp:useBean scope='session' id='userBean' class='org.akaza.openclinica.bean.login.UserAccountBean'/>
<jsp:useBean scope='session' id='userRole' class='org.akaza.openclinica.bean.login.StudyUserRoleBean' />
<jsp:useBean scope='session' id='study' class='org.akaza.openclinica.bean.managestudy.StudyBean'/>
<jsp:useBean scope='request' id='table' class='org.akaza.openclinica.web.domain.EntityBeanTable'/>

<h1><span class="title_manage"><fmt:message key="rule_manage_rule_assignment" bundle="${resworkflow}"/> <c:out value="${study.name}" /></span></h1>



<div id="ruleAssignmentsDiv">
    <jsp:include page="../include/viewRuleAssignmentTable.jsp"/>
</div>
