<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>
<jsp:useBean scope='request' id='popUpURL' class='java.lang.String'/>

<c:choose>
	<c:when test='${popUpURL != ""}'>
		openDNoteWindow('<c:out value="${popUpURL}" />');
	</c:when>
	<c:otherwise>
	</c:otherwise>
</c:choose>