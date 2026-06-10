<%--
    LibreClinica is distributed under the
    GNU Lesser General Public License (GNU LGPL).

    For details see: https://libreclinica.org/license
    copyright (C) 2026 Department of Ophthalmology and Optometry,
                        Medical University of Vienna

    Phase A2 (2026-06-10) — minimal German error page rendered by
    GlobalErrorServlet for HTML requests that hit the global
    error-page dispatcher.

    Design notes:
    - Self-contained: no taglibs beyond the trivial JSTL core tag for
      conditional rendering of the reqId pill. The session-aware
      legacy error.jsp pulls in homeheader / sidebar / login-include
      and a ResourceBundle; any of those can themselves fail
      (broken session, missing locale), and an error-page that needs
      a working session to render is the worst possible feedback loop.
      This page renders from a dead context.
    - German per the existing JSP convention; no i18n bundle.
    - The reqId pill only renders when the servlet pre-set the
      attribute to a non-empty string. Empty reqId means A4 hasn't
      yet populated MDC (or the inbound request didn't carry one),
      not a bug.
    - Inline styling: keeps the page rendering even when the static
      asset chain (CSS, web-fonts) is also down.
--%><%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %><%@
    taglib uri="jakarta.tags.core" prefix="c" %><!DOCTYPE html>
<html lang="de">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Ein Fehler ist aufgetreten - LibreClinica</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            margin: 0;
            padding: 3rem 1rem;
            background: #f5f5f5;
            color: #222;
        }
        .card {
            max-width: 32rem;
            margin: 0 auto;
            padding: 2rem;
            background: #fff;
            border-radius: 0.5rem;
            box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
        }
        h1 {
            margin: 0 0 1rem;
            font-size: 1.5rem;
            color: #b32d2e;
        }
        p {
            line-height: 1.5;
            margin: 0 0 1rem;
        }
        .pill {
            display: inline-block;
            margin: 0.5rem 0 1rem;
            padding: 0.25rem 0.75rem;
            font-family: "SF Mono", Menlo, Consolas, monospace;
            font-size: 0.85rem;
            background: #f0f0f0;
            border-radius: 0.25rem;
            color: #555;
        }
        a.home {
            display: inline-block;
            margin-top: 1rem;
            padding: 0.5rem 1rem;
            background: #2d6cb3;
            color: #fff;
            text-decoration: none;
            border-radius: 0.25rem;
        }
        a.home:hover { background: #245a99; }
    </style>
</head>
<body>
    <main class="card">
        <h1>Ein Fehler ist aufgetreten</h1>
        <p>
            Bei der Verarbeitung Ihrer Anfrage ist ein interner Fehler aufgetreten.
            Der Vorfall wurde protokolliert; bitte versuchen Sie es erneut.
        </p>
        <p>
            Sollte der Fehler wiederholt auftreten, melden Sie sich bitte beim
            Systemadministrator und geben Sie die unten angezeigte Fehler-ID an.
        </p>
        <c:if test="${not empty reqId}">
            <div class="pill">Fehler-ID: <c:out value="${reqId}"/></div>
        </c:if>
        <div>
            <a class="home" href="${pageContext.request.contextPath}/MainMenu">
                Zur Startseite
            </a>
        </div>
    </main>
</body>
</html>
