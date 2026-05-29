<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.format" var="resformat"/>

<jsp:useBean scope='session' id='userRole' class='at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean' />
<jsp:useBean scope="request" id="currRow" class="at.ac.meduniwien.ophthalmology.libreclinica.web.bean.AuditEventStudyRow" />
<c:set var="dteFormat"><fmt:message key="date_format_string" bundle="${resformat}"/></c:set>
<c:set var="dtetmeFormat"><fmt:message key="date_time_format_string" bundle="${resformat}"/></c:set>

<tr valign="top">        
      <td class="table_cell_left"><fmt:formatDate value="${currRow.bean.auditDate}" pattern="${dtetmeFormat}"/></td>
      <td class="table_cell"><c:out value="${currRow.bean.actionMessage}"/>&nbsp;</td>
      <td class="table_cell"><c:out value="${currRow.bean.auditTable}"/>&nbsp;</td>
      <td class="table_cell"><c:out value="${currRow.bean.updater.name}"/>&nbsp;</td>
      <td class="table_cell"><c:out value="${currRow.bean.subjectName}"/>&nbsp;</td>
      <td class="table_cell"><c:out value="${currRow.bean.reasonForChange}"/>&nbsp;<br/>
      <table>
		  <c:forEach var="rowChanges" items="${currRow.bean.changes}">
		  <tr valign="top"><td width="45">
      		<b><c:out value="${rowChanges.key}"/></b></td><td width="20"><c:out value="${rowChanges.value}"/></td></tr>
      	</c:forEach>
      	</table>
      </td>
      <%--<td class="table_cell"><!-- other info goes here, from context --></td>--%>      
      <td class="table_cell"><!-- actions go here. -->
		  <c:import url="../include/showAuditEntityLink.jsp">
			<c:param name="auditTable" value="${currRow.bean.auditTable}" />
			<c:param name="entityId" value="${currRow.bean.entityId}" />
			<c:param name="rolename" value="${userRole.role.name}"/>
		</c:import>
      
      </td>    
</tr> 
