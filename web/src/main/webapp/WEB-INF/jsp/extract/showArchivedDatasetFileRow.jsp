<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.format" var="resformat"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.words" var="resword"/>
<fmt:setBundle basename="at.ac.meduniwien.ophthalmology.libreclinica.i18n.notes" var="restext"/>
<c:set var="dteFormat"><fmt:message key="date_format_string" bundle="${resformat}"/></c:set>
<c:set var="dtetmeFormat"><fmt:message key="date_time_format_string" bundle="${resformat}"/></c:set>

<jsp:useBean scope="request" id="currRow" class="at.ac.meduniwien.ophthalmology.libreclinica.web.bean.ArchivedDatasetFileRow" />
<tr>
	<td class="table_cell_left"><c:out value="${currRow.bean.name}" /></td>
	<td class="table_cell"><c:out value="${currRow.bean.runTime}" /></td>
	<td class="table_cell"><c:out value="${currRow.bean.fileSize}" /></td>
	<td class="table_cell"><fmt:formatDate value="${currRow.bean.dateCreated}"/></td>
	<td class="table_cell"><c:out value="${currRow.bean.owner.name}" /></td>
    <td class="table_cell">
        <a target="_new" href="AccessFile?fileId=<c:out value="${currRow.bean.id}"/>">
            <img name="bt_Download1" src="images/bt_Download.gif" border="0" align="left" hspace="6"
                 alt="<fmt:message key="download" bundle="${resword}"/>" title="<fmt:message key="download" bundle="${resword}"/>">
        </a>
        <%-- Deleting the file posts a small form: ExportDataset refuses a GET with an action. --%>
        <form action="ExportDataset" method="post" style="display:inline; margin:0"
              onSubmit='return confirm("<fmt:message key="if_you_delete_this_dataset" bundle="${restext}"/>");'>
            <input type="hidden" name="action" value="delete"/>
            <input type="hidden" name="datasetId" value="<c:out value="${currRow.bean.datasetId}"/>"/>
            <input type="hidden" name="adfId" value="<c:out value="${currRow.bean.id}"/>"/>
            <input type="image" name="bt_Delete1" src="images/bt_Delete.gif" alt="<fmt:message key="delete" bundle="${resword}"/>"
                   title="<fmt:message key="delete" bundle="${resword}"/>" align="left" hspace="6"/>
        </form>
    </td>
</tr>
