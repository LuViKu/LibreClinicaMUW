<%--
  2026-06-29 — MUW-branded horizontal lockup for the legacy JSP header.
  Replaces the historic images/Logo.gif image. Inline SVG keeps
  the typography vector-perfect at any DPI; font-family chain falls
  back through system fonts when Google Fonts aren't loaded by the
  enclosing page.

  Eye mark: white-stroke on transparent, navy stroke, coral pupil.
  Wordmark: "LibreClinica" in Newsreader serif (navy), "MUW" in Inter
  small caps (coral). Layout cribbed from the Login Lockup design
  doc (claude.ai/design/p/f72600c7…).
--%>
<svg xmlns="http://www.w3.org/2000/svg" width="143" height="63" viewBox="0 0 143 63" role="img" aria-label="LibreClinica MUW">
  <g transform="translate(6 16)" fill="none" stroke="#111d4e" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
    <path d="M2.6 16 C9 8.2 23 8.2 29.4 16 C23 23.8 9 23.8 2.6 16 Z" />
    <circle cx="16" cy="16" r="5" />
    <circle cx="16" cy="16" r="1.9" fill="#d96849" stroke="none" />
  </g>
  <text x="46" y="38" font-family="'Newsreader', Georgia, 'Times New Roman', serif" font-size="20" font-weight="600" fill="#111d4e" letter-spacing="-0.3">LibreClinica</text>
  <text x="46" y="52" font-family="'Inter', 'Helvetica Neue', Arial, sans-serif" font-size="9.5" font-weight="600" fill="#c2553a" letter-spacing="1.2">MUW</text>
</svg>
