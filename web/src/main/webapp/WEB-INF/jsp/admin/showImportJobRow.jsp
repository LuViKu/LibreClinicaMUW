<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes" var="restext"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.format" var="resformat"/>

<jsp:useBean scope="request" id="currRow" class="at.ac.meduniwien.ophthalmology.libreclinica.web.bean.TriggerRow" />
<c:set var="dtetmeFormat"><fmt:message key="date_time_format_string" bundle="${resformat}"/></c:set>

<tr valign="top" bgcolor="#F5F5F5">
	<td class="table_cell_left"><c:out value="${currRow.bean.fullName}" /></td>
	<td class="table_cell"><fmt:formatDate value="${currRow.bean.previousDate}" pattern="${dtetmeFormat}"/></td>
	<td class="table_cell">
	<c:if test="${currRow.bean.active}">
		<fmt:formatDate value="${currRow.bean.nextDate}" pattern="${dtetmeFormat}"/>
	</c:if>
	</td>
	<td class="table_cell"><c:out value="${currRow.bean.description}" /></td>
	<td class="table_cell"><c:out value="${currRow.bean.studyName}" /></td>
	
	
	<!-- actions -->
	<%-- Pause, resume and delete post small forms: PauseJob refuses GET. --%>
	
	<td class="table_cell">
	 <table border="0" cellpadding="0" cellspacing="0">
	 <tr>
	 	<td><a href="ViewSingleJob?tname=<c:out value="${currRow.bean.fullName}" />&gname=1"><img name="bt_View1" src="images/bt_View.gif" border="0" alt="<fmt:message key="view" bundle="${resword}"/>" title="<fmt:message key="view" bundle="${resword}"/>" align="left" hspace="2"></a></td>
		<td><a href="UpdateJobImport?tname=<c:out value="${currRow.bean.fullName}" />"><img name="bt_Edit1" src="images/bt_Edit.gif" border="0" alt="<fmt:message key="edit" bundle="${resword}"/>" title="<fmt:message key="edit" bundle="${resword}"/>" align="left" hspace="2"></a></td>
		<td>
		<c:choose>
			<c:when test="${currRow.bean.active}">
				<form action="PauseJob" method="post" style="display:inline; margin:0" onSubmit='return confirm("<fmt:message key="confirm_pausing_this_job" bundle="${restext}"/>");'><input type="hidden" name="tname" value="<c:out value="${currRow.bean.fullName}" />"/><input type="hidden" name="gname" value="1"/><input type="image" title="<fmt:message key="remove" bundle="${resword}"/>" src="images/bt_Remove.gif" alt="<fmt:message key="remove" bundle="${resword}"/>" align="left" hspace="2"/></form>
			</c:when>
			<c:otherwise>
				<form action="PauseJob" method="post" style="display:inline; margin:0" onSubmit='return confirm("<fmt:message key="confirm_restoring_this_job" bundle="${restext}"/>");'><input type="hidden" name="tname" value="<c:out value="${currRow.bean.fullName}" />"/><input type="hidden" name="gname" value="1"/><input type="image" title="<fmt:message key="restore" bundle="${resword}"/>" src="images/bt_Restore.gif" alt="<fmt:message key="restore" bundle="${resword}"/>" align="left" hspace="2"/></form>
			</c:otherwise>
		</c:choose>
		</td>&nbsp;
		<c:if test="${userBean.sysAdmin}">
			<td>
				<form action="PauseJob" method="post" style="display:inline; margin:0" onSubmit='return confirm("<fmt:message key="confirm_deleting_this_job" bundle="${restext}"/>");'><input type="hidden" name="tname" value="<c:out value="${currRow.bean.fullName}" />"/><input type="hidden" name="gname" value="1"/><input type="hidden" name="del" value="y"/><input type="image" title="<fmt:message key="delete" bundle="${resword}"/>" src="images/bt_Delete.gif" alt="<fmt:message key="delete" bundle="${resword}"/>" align="left" hspace="2"/></form>
			</td>
		</c:if>
	 </tr>
		</table>
	</td>
</tr>