"""
Generate LegacyServletRegistry.java from web.xml.

Reads the 222 <servlet> + <servlet-mapping> entries and emits one
@Bean ServletRegistrationBean per servlet. Output goes to:
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

# Filter: skip the `pages` DispatcherServlet (stays in web.xml's <servlet>),
# skip the `ws` MessageDispatcherServlet (zombie — verify before deleting),
# skip the 2 Jersey servlets (zombies).
skipped = set()
for n, d in servlet_map.items():
    if d['cls'] == 'org.springframework.web.servlet.DispatcherServlet':
        skipped.add(n)
    elif 'spring.ws' in d['cls'] or 'jersey' in d['cls'].lower():
        skipped.add(n)

# Collect needed imports (classes referenced in @Bean methods)
imports = set()
for n, d in servlet_map.items():
    if n in skipped:
        continue
    imports.add(d['cls'])

# Generate java
lines = []
lines.append('package at.ac.meduniwien.ophthalmology.libreclinica.config;')
lines.append('')
lines.append('import org.springframework.boot.web.servlet.ServletRegistrationBean;')
lines.append('import org.springframework.context.annotation.Bean;')
lines.append('import org.springframework.context.annotation.Configuration;')
lines.append('')
for cls in sorted(imports):
    lines.append(f'import {cls};')
lines.append('')
lines.append('/**')
lines.append(' * Phase C.16 (2026-05-30): Java replacement for the 218 legacy LibreClinica')
lines.append(' * servlet declarations in {@code web.xml}. Each {@code @Bean ServletRegistrationBean}')
lines.append(' * mirrors one {@code <servlet>} + {@code <servlet-mapping>} pair from the')
lines.append(' * pre-cliff web.xml. Generated mechanically from the auto-generated')
lines.append(' * inventory at')
lines.append(' * {@code docs/development/modernization/phase-c14-web-xml-inventory.md}.')
lines.append(' * <p>')
lines.append(' * Skipped:')
lines.append(' * <ul>')
lines.append(' *   <li>{@code pages} ({@code DispatcherServlet}) — stays as a {@code <servlet>}')
lines.append(' *       entry in {@code web.xml} until C.16 finishes the WAR→JAR flip; serves')
lines.append(' *       its child context via {@code config.WebMvcConfig}.</li>')
lines.append(' *   <li>{@code ws} ({@code MessageDispatcherServlet}) + {@code OpenClinicaJersey} /')
lines.append(' *       {@code OpenClinicaJersey2} ({@code SpringServlet}) — flagged as zombie')
lines.append(' *       candidates in the cliff inventory; the underlying frameworks fail to')
lines.append(' *       link against jakarta.servlet 6 ({@code NoClassDefFoundError: javax.servlet.Filter}).')
lines.append(' *       Tomcat marks them unavailable at deploy time. They stay as web.xml')
lines.append(' *       entries (Tomcat skips the broken servlets gracefully) until verified')
lines.append(' *       as unused and removed in a follow-up.</li>')
lines.append(' * </ul>')
lines.append(' */')
lines.append('@Configuration')
lines.append('public class LegacyServletRegistry {')
lines.append('')

method_count = 0
for name in sorted(servlet_map):
    if name in skipped:
        continue
    d = servlet_map[name]
    cls_short = d['cls'].split('.')[-1]
    method_name = name[0].lower() + name[1:] if name else 'servlet'
    # ensure valid Java identifier — replace non-identifier chars
    method_name = re.sub(r'[^a-zA-Z0-9_]', '_', method_name)
    if not method_name[0].isalpha():
        method_name = 'servlet_' + method_name
    method_name = method_name + 'Registration'
    if not d['urls']:
        urls_arg = '/* unmapped */ ""'
    else:
        urls_arg = ', '.join(f'"{u}"' for u in d['urls'])
    lines.append(f'    @Bean')
    lines.append(f'    public ServletRegistrationBean<{cls_short}> {method_name}() {{')
    lines.append(f'        ServletRegistrationBean<{cls_short}> reg =')
    lines.append(f'                new ServletRegistrationBean<>(new {cls_short}(), {urls_arg});')
    lines.append(f'        reg.setName("{name}");')
    if d['load'] is not None:
        lines.append(f'        reg.setLoadOnStartup({d["load"]});')
    for p_name, p_value in d['init_params']:
        lines.append(f'        reg.addInitParameter("{p_name}", "{p_value}");')
    lines.append(f'        return reg;')
    lines.append(f'    }}')
    lines.append(f'')
    method_count += 1

lines.append('}')

with open(OUT_PATH, 'w') as out:
    out.write('\n'.join(lines))

print(f'wrote {OUT_PATH}')
print(f'methods: {method_count}')
print(f'skipped: {sorted(skipped)}')
