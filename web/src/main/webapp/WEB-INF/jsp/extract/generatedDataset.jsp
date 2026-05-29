<%--
<%@ page contentType="text/html; charset=UTF-8" %
--%>
<%@page contentType="text/plain"%>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<jsp:useBean scope="request" id="generate" class="java.lang.String"/>
<c:out value="${generate}"/>
