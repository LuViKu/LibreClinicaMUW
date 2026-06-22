# Phase B.2 — Eclipse Transformer dry run

**Run date:** 2026-05-28
**Branch:** `feature/phase-b-eclipse-transformer-dry-run` (off `feature/phase-b-jakarta-cliff` @ `e500f6513`)
**Tool:** [Eclipse Transformer](https://github.com/eclipse/transformer) 0.5.0 CLI, default Jakarta rules
**Input:** `git ls-files` tracked files (3966 files, ~150K LOC) — excludes `.m2-cache/`, `target/`, build artifacts
**Companion docs:** [phase-b-execution-playbook.md § B.2](phase-b-execution-playbook.md#b2--eclipse-transformer-dry-run), [decision-record.md DR-006/DR-010](decision-record.md)

This is a **throwaway dry-run** to scope the manual reconciliation work that B.3+ will do for real. Per the playbook, only this report is committed; the transformer-modified files are not.

---

## TL;DR

- **249 of 3966 files would change** (~6%). All in `src/main/`; **zero test files affected**.
- **Zero unconvertible sites** at the file level — Transformer's `Failed [0]` row + a post-run sweep for leftover `javax.*` imports both come back empty.
- The work is mechanical for the namespaces Transformer's default ruleset covers (`javax.servlet`, `javax.persistence`, `javax.ws.rs`, `javax.mail`, `javax.annotation`, `javax.xml.bind`).
- **Five known reconciliation gaps Transformer cannot handle** — itemised in [§ Reconciliation gaps](#reconciliation-gaps-transformer-cannot-handle) below. These are the items B.3+ must address by hand.
- **Green light to start B.3.** No surprises in the Transformer output that would change the playbook's order.

---

## Method

```sh
# Build the transformer-cli classpath via a tiny wrapper pom
mkdir -p /tmp/transformer-wrapper && cat > /tmp/transformer-wrapper/pom.xml <<'POMEOF'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>local</groupId><artifactId>transformer-wrapper</artifactId><version>1</version>
  <packaging>pom</packaging>
  <dependencies><dependency>
    <groupId>org.eclipse.transformer</groupId>
    <artifactId>org.eclipse.transformer.cli</artifactId>
    <version>0.5.0</version>
  </dependency></dependencies>
</project>
POMEOF
docker run --rm -v /tmp/transformer-wrapper:/work -v "$(pwd)/.m2-cache":/root/.m2 \
  -w /work maven:3-eclipse-temurin-21 mvn -B -ntp dependency:build-classpath \
  -Dmdep.outputFile=/work/cp.txt

# Prepare an input tree from git-tracked files only
mkdir -p /tmp/b2-input
git ls-files | tar -cf - -T - | tar -xf - -C /tmp/b2-input

# Run Transformer (input → output, verbose, log to file)
CP=$(cat /tmp/transformer-wrapper/cp.txt)
docker run --rm -v "$(pwd)/.m2-cache":/root/.m2 -v /tmp:/tmp -w /tmp \
  maven:3-eclipse-temurin-21 java -cp "$CP" \
  org.eclipse.transformer.cli.JakartaTransformerCLI \
  /tmp/b2-input /tmp/b2-output -v -lf /tmp/b2-transformer.log -ll debug
```

Run completes in ~30s on cached Maven artifacts. Output is `Transformer Return Code [ 0 ] [ Success ]`.

---

## Top-line stats (Transformer summary)

```
[  All Resources ] [   4847 ] Unaccepted [      0 ]   Accepted [   4847 ]
[   All Accepted ] [   4847 ] Unselected [      0 ]   Selected [   3966 ]
[   All Selected ] [   4847 ]  Unchanged [   4598 ]    Changed [    249 ]
[  All Unchanged ] [   4598 ]     Failed [      0 ] Duplicated [      0 ]
[    All Changed ] [    249 ]    Renamed [      0 ]    Content [    249 ]
[ Actions ]
[   Class Action ] [    793 ]  Unchanged [    786 ]    Changed [      7 ]
[     Jar Action ] [      2 ]  Unchanged [      1 ]    Changed [      1 ]
[  Rename Action ] [   1946 ]  Unchanged [   1946 ]    Changed [      0 ]
[    Text Action ] [     13 ]  Unchanged [     13 ]    Changed [      0 ]
[ Properties Action ] [     85 ]  Unchanged [     85 ]    Changed [      0 ]
[     XML Action ] [    289 ]  Unchanged [    284 ]    Changed [      5 ]
[ Manifest Action ] [      2 ]  Unchanged [      2 ]    Changed [      0 ]
[     JSP Action ] [    413 ]  Unchanged [    412 ]    Changed [      1 ]
[    Java Action ] [   1304 ]  Unchanged [   1069 ]    Changed [    235 ]
```

The 793 `Class Action`s and 7 changed `.class` files come from **inside the two changed JARs** (no committed loose `.class` files). The 4847 → 3966 "Selected" delta is just static/binary files (gif/png/etc.) Transformer leaves untouched by default.

---

## By module

```
116 core/src
110 web/src
 12 ws/src
  1 ws/pom.xml
  1 web/pom.xml
  1 pom.xml
  1 odm/pom.xml
  1 core/pom.xml
```

Every module pom touched (groupId/artifactId renames; see [§ pom.xml example](#pomxml-example)). No `docs/` changes. **No test sources changed** anywhere.

---

## By file extension

```
235 java
  5 xml   (the 5 pom.xml files)
  2 jar   (vestigial JDBC drivers — see § gap 4)
  1 jsp   (configurationPasswordRequirements.jsp — Java import in <%@ page import="…" %>)
```

---

## Top `javax.*` packages rewritten

Counts of import statements rewritten across the 235 Java files:

| Count | Package | Notes |
|------:|---------|-------|
|   657 | `javax.persistence`  | JPA / Hibernate ORM — the largest single surface; B.5 territory |
|   178 | `javax.servlet.http` | HttpServletRequest / Response — controllers + 295 legacy servlets |
|    58 | `javax.servlet`      | Servlet, Filter, ServletException |
|    33 | `javax.ws.rs`        | JAX-RS REST endpoints |
|    17 | `javax.servlet.jsp`  | JSP API |
|    14 | `javax.mail.internet`| MimeMessage |
|    10 | `javax.mail`         | Session, Message |
|     4 | `javax.xml.bind`     | JAXB — already partially migrated for JDK 11 (per pre-Phase-B fix PR #440) |
|     2 | `javax.annotation`   | @PostConstruct / @Resource |

After the run, a fresh `grep -rE "^import javax\.(servlet\|persistence\|ws\.rs\|mail\|annotation\|xml\.bind\|activation)" /tmp/b2-output --include='*.java'` returns **0 matches**. Nothing leaks through.

---

## Sample diffs

### Java import

```diff
--- a/core/src/main/java/org/akaza/openclinica/bean/extract/DownloadDiscrepancyNote.java
+++ b/core/src/main/java/org/akaza/openclinica/bean/extract/DownloadDiscrepancyNote.java
@@ -16,8 +16,8 @@
-import javax.servlet.ServletOutputStream;
-import javax.servlet.http.HttpServletResponse;
+import jakarta.servlet.ServletOutputStream;
+import jakarta.servlet.http.HttpServletResponse;
```

### pom.xml example

```diff
--- a/core/pom.xml
+++ b/core/pom.xml
@@ -115,15 +115,15 @@
 		<dependency>
-			<groupId>javax.servlet</groupId>
+			<groupId>jakarta.servlet</groupId>
 			<artifactId>servlet-api</artifactId>
 		</dependency>
 		<dependency>
 			<groupId>com.sun.mail</groupId>
-			<artifactId>javax.mail</artifactId>
+			<artifactId>jakarta.mail</artifactId>
 		</dependency>
         <dependency>
-        	<groupId>javax.activation</groupId>
+        	<groupId>jakarta.activation</groupId>
 			<artifactId>activation</artifactId>
         </dependency>
```

Transformer rewrites the `groupId`/`artifactId` coordinates but **does NOT bump versions** (gap 2 below).

### JSP `<%@ page import="…" %>`

```diff
--- a/web/src/main/webapp/WEB-INF/jsp/admin/configurationPasswordRequirements.jsp
+++ b/web/src/main/webapp/WEB-INF/jsp/admin/configurationPasswordRequirements.jsp
@@ -1,5 +1,5 @@
 <%@ page contentType="text/html; charset=UTF-8"
-         import="javax.servlet.http.HttpServletRequest,
+         import="jakarta.servlet.http.HttpServletRequest,
                  java.util.Map" %>
```

The other 412 JSPs were unchanged — they reference servlet/JSP API via taglibs, not via `<%@ page import %>`. (The taglib URIs themselves are gap 3 below.)

---

## Reconciliation gaps Transformer cannot handle

These are the **manual** items B.3+ owns. None is a surprise; they're called out so the playbook's sub-phase scope is grounded in evidence.

### Gap 1 — Castor 1.4.1 replacement (B.3, [DR-006](decision-record.md#dr-006--castor-replacement-jakarta-jaxb))

Transformer renames `javax.xml.bind` → `jakarta.xml.bind` (4 imports) but **does not touch Castor**, which has no Jakarta-namespace variant and must be swapped out wholesale. The B.0 characterisation tests are the regression net for the swap.

### Gap 2 — `pom.xml` Jakarta-namespace **version** bumps

Transformer rewrites groupId/artifactId (`javax.servlet:servlet-api` → `jakarta.servlet:servlet-api`) but the `<version>` element is whatever was there before — i.e. the **3.x/4.x** Jakarta coordinates with stub artifacts that don't exist. The actual replacements need explicit versions, e.g.:

| Old | New | Pinned via |
|-----|-----|-----------|
| `javax.servlet:servlet-api:3.0.1` | `jakarta.servlet:jakarta.servlet-api:6.0.0` | B.6 (Tomcat 10/11 = Servlet 6) |
| `javax.persistence:persistence-api:1.0` | `jakarta.persistence:jakarta.persistence-api:3.1.0` | B.5 (Hibernate 6) |
| `javax.mail:javax.mail-api:1.6.x` | `jakarta.mail:jakarta.mail-api:2.1.x` | B.9 |
| `javax.activation:activation:1.1` | `jakarta.activation:jakarta.activation-api:2.1.x` | B.9 |
| `javax.ws.rs:javax.ws.rs-api:2.x` | `jakarta.ws.rs:jakarta.ws.rs-api:3.1.0` | B.9 |

The companion [phase-b-dependency-analysis.md](phase-b-dependency-analysis.md) has the full mapping.

### Gap 3 — JSP / JSTL taglib URIs (B.7)

Transformer leaves the taglib URIs in `<%@ taglib uri="…" %>` directives **untouched**. The pre/post URI sets are byte-identical:

```
http://java.sun.com/jsp/jstl/core
http://java.sun.com/jsp/jstl/fmt
http://java.sun.com/jsp/jstl/functions
http://java.sun.com/jstl/core
http://java.sun.com/jstl/fmt
http://www.opensymphony.com/sitemesh/decorator
http://www.springframework.org/tags/form
com.akazaresearch.tags
com.akazaresearch.viewtags
```

For Jakarta JSTL 3.0 + Jakarta Tags, the `http://java.sun.com/jsp/jstl/*` URIs must change to `jakarta.tags.*` across all 413 JSPs. Mechanical (`sed`-able) but Transformer doesn't do it. Scoped to B.7.

### Gap 4 — `web.xml` schema URIs (B.6)

The 2 `web.xml` files (`web/`, `ws/`) are **unchanged** in the transformer output. The `xmlns="http://xmlns.jcp.org/xml/ns/javaee" version="4.0"` headers need updating to `xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0"` when Tomcat 9 → 10/11 lands in B.6. Two-file `sed` job.

### Gap 5 — 2 vestigial JDBC driver JARs

Transformer rewrites bytecode references inside `web/src/main/config/libraries/ojdbc14-10g.jar` (Oracle JDBC, 2006) and `postgresql-8.1-405.jdbc3.jar` (Postgres JDBC, 2006). Verified empirically: the Oracle jar drops from 400 to 357 `javax/`-prefixed string references after transform; the postgres jar is byte-different but its 89 `javax/` references count is unchanged.

**Recommendation:** these two jars are deployment-time fixtures in `web/src/main/config/libraries/` — not on the Maven classpath, not exercised by integration tests, and from 2006. They should be **removed** in B.3 cleanup rather than carried forward with patched bytecode. Open follow-up: confirm with the deployment team that neither jar is referenced by an install-time symlink before deleting.

---

## What this means for B.3+

| Sub-phase | Confidence | Why |
|-----------|-----------|-----|
| **B.3 Castor → JAXB** | medium | Transformer can't help. Relies entirely on the B.0 characterisation tests as the regression net. |
| **B.4 Spring 5 → 6 + Security 5 → 6** | high | Mechanical for the Java code (235 files); the harder work is Spring's own API breakage, which is independent of namespace translation. |
| **B.5 Hibernate 5.6 → 6.4** | high (mechanically) / medium (semantically) | 657 `javax.persistence` rewrites are clean. The real risk is Hibernate 6's stricter HQL + `Criteria` API removal — pre-existing [item 2 backlog test](../../MIGRATION.md) covers the latter. |
| **B.6 Tomcat 9 → 10/11** | high | Servlet rewrites are clean (235 files). 2 `web.xml` headers need a manual edit (gap 4). |
| **B.7 JSP/JSTL taglibs** | medium | 412 of 413 JSPs are byte-identical post-Transformer; only the taglib URIs change. A targeted `sed` across the JSP tree, not full Transformer. |
| **B.8 Apache Commons jakarta** | high | Out of Transformer's scope (different artifact rename game), but mechanical. |
| **B.9 Mail / activation / JAX-RS** | high | 4 + 24 imports total; small surface. |
| **B.10 Joda-Time → java.time** | medium | Orthogonal to Transformer — Joda-Time isn't `javax.*`. Source rewriting via OpenRewrite recipe. |
| **B.11 Java package rename ([DR-010](decision-record.md#dr-010--java-package-rename-to-muw-namespace-during-phase-b11))** | low-medium | Transformer doesn't do package renames. IntelliJ structural-replace on the Phase B integration branch, immediately after Transformer's javax→jakarta sweep lands. |

The biggest take-away: **the 5 gaps above are concentrated, scoped, and small**. None warrants resequencing the playbook.

---

## Open follow-ups (post-B.2, pre-B.3)

- [ ] Confirm deployment use (or non-use) of `web/src/main/config/libraries/{ojdbc14-10g,postgresql-8.1-405.jdbc3}.jar` — likely candidates for removal in B.3.
- [ ] Decide whether to vendor Transformer 0.5.0 (Maven plugin) or invoke standalone in B.3. Plugin would bind to a build phase; standalone keeps the throwaway pattern. Standalone preferred (the dry-run-via-CLI recipe above is reproducible).
- [ ] Capture the next dry run on the post-B.3 tree (Castor → JAXB) to confirm the residual `javax.xml.bind` references collapse to zero before B.4 starts.

---

## Reproducing this dry run

The recipe in [§ Method](#method) is fully reproducible: deterministic input (`git ls-files`), pinned Transformer version, isolated `/tmp/b2-input` + `/tmp/b2-output` trees. Run takes ~30s on warm Maven cache, ~3min cold.
