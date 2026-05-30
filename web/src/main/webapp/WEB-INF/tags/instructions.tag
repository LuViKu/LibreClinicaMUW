<%@tag body-content="scriptless" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>

<c:forEach var="instruction" items="${instructions}">
      ${instruction}<br>
</c:forEach>

