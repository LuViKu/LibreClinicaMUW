<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>

<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>

<c:import url="../include/admin-header.jsp"/>


<jsp:include page="../include/sidebar.jsp"/>

<jsp:useBean scope='session' id='userBean' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean'/>

<jsp:useBean scope='request' id='table' class='at.ac.meduniwien.ophthalmology.libreclinica.web.bean.EntityBeanTable'/>
<jsp:useBean scope='request' id='auditUserBean' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean'/>
 <h1><span class="title_manage">
 <fmt:message key="view_user_log_for" bundle="${resword}"/>
 <c:out value="${auditUserBean.name}"/> (<c:out value="${auditUserBean.firstName}"/>&nbsp;<c:out value="${auditUserBean.lastName}"/>)
 </span></h1>

<jsp:include page="../include/alertbox.jsp" />
<c:import url="../include/showTable.jsp"><c:param name="rowURL" value="showAuditEventRow.jsp" /></c:import>
<jsp:include page="../include/footer.jsp"/>
