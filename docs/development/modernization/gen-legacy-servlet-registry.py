"""
Generate LegacyServletRegistry.java from web.xml.

Reads the 222 <servlet> + <servlet-mapping> entries and emits one
ServletContextInitializer @Bean that registers each legacy servlet via
ServletContext.addServlet(name, Class) for LAZY instantiation —
matches the legacy web.xml lifecycle (Tomcat creates the servlet
instance on first request, after the application context is fully
initialised). Pre-instantiating via `new ServletRegistrationBean(new
ServletClass())` at @Bean creation time fires the servlets' static
initializers too early — e.g., CreateDiscrepancyNoteServlet's static
resexception ResourceBundle reference NPEs.

Output goes to:
  web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/LegacyServletRegistry.java
"""

import re

WEB_XML = '/Users/lukas/LibreClinicaMUW/modernization/web/src/main/webapp/WEB-INF/web.xml'
OUT_PATH = '/Users/lukas/LibreClinicaMUW/modernization/web/src/main/java/at/ac/meduniwien/ophthalmology/libreclinica/config/LegacyServletRegistry.java'

with open(WEB_XML) as f:
    content = f.read()

blocks = re.findall(r'<servlet>(.*?)</servlet>', content, re.DOTALL)
mappings = re.findall(r'<servlet-mapping>(.*?)</servlet-mapping>', content, re.DOTALL)

servlet_map = {}
for b in blocks:
    name = re.search(r'<servlet-name>([^<]+)</servlet-name>', b)
    cls = re.search(r'<servlet-class>([^<]+)</servlet-class>', b)
    load = re.search(r'<load-on-startup>(\d+)</load-on-startup>', b)
    init_params = re.findall(r'<init-param>\s*<param-name>([^<]+)</param-name>\s*<param-value>([^<]+)</param-value>\s*</init-param>', b)
    if name and cls:
        servlet_map[name.group(1).strip()] = {
            'cls': cls.group(1).strip(),
            'load': int(load.group(1)) if load else None,
            'init_params': init_params,
            'urls': [],
        }

for m in mappings:
    name = re.search(r'<servlet-name>([^<]+)</servlet-name>', m)
    urls = re.findall(r'<url-pattern>([^<]+)</url-pattern>', m)
    if name and name.group(1).strip() in servlet_map:
        servlet_map[name.group(1).strip()]['urls'].extend(urls)

DEAD_SERVLET_CLASSES = {
    'at.ac.meduniwien.ophthalmology.libreclinica.control.urlRewrite.UrlRewriteServlet',
    'at.ac.meduniwien.ophthalmology.libreclinica.web.openrosa.OpenRosaFormDownloadServlet',
    'at.ac.meduniwien.ophthalmology.libreclinica.control.submit.DataEntryServlet',  # abstract
}

skipped = set()
for n, d in servlet_map.items():
    if d['cls'] == 'org.springframework.web.servlet.DispatcherServlet':
        skipped.add(n)
    elif d['cls'].startswith('org.springframework.ws.') or 'jersey' in d['cls'].lower():
        skipped.add(n)
    elif d['cls'] in DEAD_SERVLET_CLASSES:
        skipped.add(n)

imports = set()
for n, d in servlet_map.items():
    if n in skipped:
        continue
    imports.add(d['cls'])

lines = []
lines.append('package at.ac.meduniwien.ophthalmology.libreclinica.config;')
lines.append('')
lines.append('import jakarta.servlet.ServletRegistration;')
lines.append('import org.springframework.boot.web.servlet.ServletContextInitializer;')
lines.append('import org.springframework.context.annotation.Bean;')
lines.append('import org.springframework.context.annotation.Configuration;')
lines.append('')
for cls in sorted(imports):
    lines.append(f'import {cls};')
lines.append('')
lines.append('/**')
lines.append(' * Phase C.16 (2026-05-30): Java replacement for the 215 legacy LibreClinica')
lines.append(' * servlet declarations in {@code web.xml}. Single')
lines.append(' * {@link ServletContextInitializer} @Bean registers each legacy servlet via')
lines.append(' * {@code ServletContext.addServlet(name, Class)} — Tomcat then')
lines.append(' * <strong>lazily</strong> instantiates the servlet on its first request,')
lines.append(' * matching the original web.xml lifecycle. Pre-instantiating via')
lines.append(' * {@code new ServletRegistrationBean(new MyServlet())} at @Bean creation')
lines.append(' * time fires servlet static initializers too early (some reference')
lines.append(' * ResourceBundles loaded later by the application context — e.g.')
lines.append(' * {@code CreateDiscrepancyNoteServlet.resexception}).')
lines.append(' * <p>')
lines.append(' * Skipped:')
lines.append(' * <ul>')
lines.append(' *   <li>{@code pages} ({@code DispatcherServlet}) — stays as a {@code <servlet>}')
lines.append(' *       entry in {@code web.xml}; loads pages-servlet.xml → WebMvcConfig.</li>')
lines.append(' *   <li>{@code ws} ({@code MessageDispatcherServlet}) + {@code OpenClinicaJersey} /')
lines.append(' *       {@code OpenClinicaJersey2} ({@code SpringServlet}) — zombies; underlying')
lines.append(' *       frameworks reference javax.servlet.Filter and fail to link against')
lines.append(' *       jakarta.servlet 6. Tomcat marked them unavailable at deploy time.</li>')
lines.append(' *   <li>{@code DataEntryServlet} — abstract; concrete subclasses')
lines.append(' *       (InitialDataEntryServlet, etc.) handle the actual routes.</li>')
lines.append(' *   <li>{@code urlRewriterServlet} ({@code UrlRewriteServlet}) +')
lines.append(' *       {@code OpenRosaFormDownloadServlet} — class no longer exists in')
lines.append(' *       the source tree (removed in earlier phases without web.xml cleanup).</li>')
lines.append(' * </ul>')
lines.append(' */')
lines.append('@Configuration')
lines.append('public class LegacyServletRegistry {')
lines.append('')
lines.append('    @Bean')
lines.append('    public ServletContextInitializer legacyServletsInitializer() {')
lines.append('        return ctx -> {')

method_count = 0
for name in sorted(servlet_map):
    if name in skipped:
        continue
    d = servlet_map[name]
    cls_short = d['cls'].split('.')[-1]
    urls = d['urls']
    var = f'reg{method_count}'
    lines.append(f'            ServletRegistration.Dynamic {var} = ctx.addServlet("{name}", {cls_short}.class);')
    if urls:
        url_args = ', '.join(f'"{u}"' for u in urls)
        lines.append(f'            {var}.addMapping({url_args});')
    if d['load'] is not None:
        lines.append(f'            {var}.setLoadOnStartup({d["load"]});')
    for p_name, p_value in d['init_params']:
        # Escape backslashes + double-quotes for Java string literal
        p_value_escaped = p_value.replace('\\', '\\\\').replace('"', '\\"')
        lines.append(f'            {var}.setInitParameter("{p_name}", "{p_value_escaped}");')
    lines.append('')
    method_count += 1

lines.append('        };')
lines.append('    }')
lines.append('}')

with open(OUT_PATH, 'w') as out:
    out.write('\n'.join(lines))

print(f'wrote {OUT_PATH}')
print(f'servlets registered: {method_count}')
print(f'skipped: {sorted(skipped)}')
