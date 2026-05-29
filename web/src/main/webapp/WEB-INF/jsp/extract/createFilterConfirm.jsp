<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>

<jsp:include page="../include/extract-header.jsp"/>


<jsp:include page="../include/sidebar.jsp"/>

<jsp:useBean scope='session' id='userBean' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean'/>
<jsp:useBean scope="request" id="dataset" class="at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean"/>

<h1><span class="title_manage"><fmt:message key="view_datasets" bundle="${resword}"/></span></h1>

<jsp:include page="../include/footer.jsp"/>
