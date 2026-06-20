<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>
<%@ taglib uri="http://www.opensymphony.com/sitemesh/decorator" prefix="decorator" %>
<%@ taglib uri="com.akazaresearch.viewtags" prefix="view" %>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.format" var="resformat"/>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN"
"http://www.w3.org/TR/html4/loose.dtd">
<html lang="en">
<head>
    <meta http-equiv="content-type" content="text/html; charset=utf-8">
    <meta name="gwt:property" content="locale=${pageContext.request.locale}">
    <title><decorator:title default="LibreClinica MUW Ophthalmology" /></title>
    <script type="text/javascript" language="javascript" src="../gwt/GwtMenu/at.ac.meduniwien.ophthalmology.libreclinica.gwt.GwtMenu.nocache.js"></script>
    <script type="text/javascript" language="javascript" src="../includes/prototype.js"></script>
    <script type="text/javascript" language="javascript" src="../includes/global_functions_javascript.js"></script>
    <script type="text/javascript" language="javascript" src="../includes/Tabs.js"></script>
    <!-- Added for the new Calender -->

    <link rel="stylesheet" type="text/css" media="all" href="../includes/new_cal/skins/aqua/theme.css" title="Aqua" />
    <script type="text/javascript" src="../includes/new_cal/calendar.js"></script>
    <script type="text/javascript" src="includes/new_cal/lang/calendar-en.js"></script>
    <script type="text/javascript" src="../includes/new_cal/lang/<fmt:message key="jscalendar_language_file" bundle="${resformat}"/>"></script>
    <script type="text/javascript" src="../includes/new_cal/calendar-setup.js"></script>
    <!-- End -->
    <link rel="stylesheet" href="../includes/styles_updated.css" type="text/css">
    <link rel="stylesheet" href="../includes/proto_styles.css" type="text/css">
    <link rel="stylesheet" href="../gwt/GwtMenu/GwtMenu.css" type="text/css">
    <decorator:head />
</head>
<body>
<div id="headerDiv">
    <fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>
    <div id="logoDiv">
        <img src="../images/Logo.gif" alt="LibreClinica"/>
        <span class="muw-strapline" style="margin-left:14px; font-size:13px; color:#444; vertical-align:middle;">
            Department of Ophthalmology and Optometry &middot; Medical University of Vienna
        </span>
    </div>
    <!-- the sub-menu, or alternative menu, displays if JavaScript is disabled-->
    <div id="menuContainer">
        <noscript>
            <span class="noscript">
                <a href="MainMenu"><fmt:message key="nav_home" bundle="${resword}"/></a> | <a href="ListStudySubjectsSubmit"><fmt:message key="nav_submit_data" bundle="${resword}"/></a> | <a href="ExtractDatasetsMain"><fmt:message key="nav_extract_data" bundle="${resword}"/></a> | <a href="ManageStudy"><fmt:message key="manage_study" bundle="${resword}"/></a> | <a href="AdminSystem"><fmt:message key="bussines_admin" bundle="${resword}"/></a>
            </span>
        </noscript>

    </div>

    <div id="reportIssueDiv">
        
    </div>
    <div id="userBoxDiv" class="userbox">
        <view:userbox />
    </div>
</div>
<%-- this element must be designed to optionally include/exclude its internal DIVs --%>
<view:sidebar />

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%-- Phase E.8 legacy-retirement (2026-06-20) — deprecation banner.
     Rendered only when LegacyServletTelemetryFilter set the attributes
     (i.e. the path is a cataloged "safe to delete" servlet AND
     LIBRECLINICA_LEGACY_BANNER=true). Static markup so it does NOT
     execute scripts or query the DB; pure i18n + URL hand-off. --%>
<c:if test="${not empty requestScope['muw.legacyDeprecation.bannerVisible']}">
    <div id="legacyDeprecationBanner"
         style="background:#fff7e6;border-bottom:2px solid #d97706;
                color:#7c2d12;padding:10px 16px;font-size:13px;
                line-height:1.4;font-family:Arial,sans-serif;">
        <strong>⚠ Deprecated page —</strong>
        please switch to the new SPA:
        <a href="<c:url value='${requestScope[\"muw.legacyDeprecation.spaRoute\"]}'/>"
           style="color:#7c2d12;text-decoration:underline;font-weight:bold;">
            <c:out value="${requestScope['muw.legacyDeprecation.spaRoute']}"/>
        </a>.
        Scheduled removal:
        <strong><c:out value="${requestScope['muw.legacyDeprecation.sunsetDate']}"/></strong>
        (bucket: <c:out value="${requestScope['muw.legacyDeprecation.bucket']}"/>).
    </div>
</c:if>

<div id="bodyDiv">
    <decorator:body />
    <div id="workflowDiv">
        <view:workflow />
    </div>
</div>

<div id="footerDiv">
</div>
</body>
</html>
