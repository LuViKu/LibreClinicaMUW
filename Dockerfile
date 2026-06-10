FROM docker.io/library/maven:3-eclipse-temurin-21 AS builder

# SKIP_SPA gates the frontend-maven-plugin SPA build (web/pom.xml:23
# `<skipSpa>false</skipSpa>` is the maven default). Default `true` here
# preserves the fast-path used by `docker compose up` and the smoke-test
# job in .github/workflows/build.yml. The release-image workflow
# (.github/workflows/release-image.yml) passes --build-arg SKIP_SPA=false
# so production WARs contain the SPA bundle.
ARG SKIP_SPA=true

WORKDIR /app

# ----- Dependency layer -----
#
# Copy every pom.xml first, in a separate COPY for each module. As long as
# none of the poms change, Docker re-uses the next layer (which downloads all
# Maven dependencies) from cache. The BuildKit cache mount on /root/.m2 also
# preserves the downloaded jars across runs on the same builder, and the
# GHA-backed buildx cache (see .github/workflows/build.yml) makes the same
# /root/.m2 cache survive across GitHub-hosted runners.
#
# Result: a no-source-change CI run skips Maven dependency download entirely
# (~30 seconds vs ~5 minutes from cold).
COPY pom.xml ./
COPY core/pom.xml core/
COPY odm/pom.xml odm/
COPY docs/pom.xml docs/
COPY web/pom.xml web/

RUN --mount=type=cache,target=/root/.m2 \
    set -eux; \
    # dependency:go-offline + de.qaware.maven:go-offline-maven-plugin would
    # be the canonical "pre-fetch every dep" combo, but the plugin pulls
    # extra setup. dependency:go-offline alone covers the bulk of artifacts
    # for this multi-module build. The `|| true` tolerates the handful of
    # plugins that report missing-artifact metadata even after a clean
    # download (notably jaxb2 and jrebel) - the actual `mvn package` run
    # below re-resolves them with a working cache.
    mvn -B -ntp dependency:go-offline -DskipTests=true || true

# ----- Build layer -----
COPY . .

RUN --mount=type=cache,target=/root/.m2 \
    set -eux; \
    mvn package -DskipSpa=${SKIP_SPA} -DskipTests=true -Dmdep.analyze.skip=true; \
    # Maven's default WAR name is ${artifactId}-${version}.war, so this is
    # LibreClinica-web-1.4.0rc1-muw.war today and will keep changing as the
    # project version moves. Glob it and rename to a stable name for the
    # COPY --from=builder line below.
    mv web/target/LibreClinica-web-*.war /LibreClinica-web.war;

############################################################
FROM tomcat:10-jdk21

# Phase B.1 JDK 21 baseline: legacy Spring/Hibernate reflection needs java.base
# opens, Castor 1.4.1's BaseXercesJDK5Serializer touches an internal JDK class
# (com.sun.org.apache.xml.internal.serialize.XMLSerializer) that needs java.xml
# exports, and Spring-LDAP's AbstractContextSource references com.sun.jndi.ldap.LdapCtxFactory
# from java.naming. All three are stopgaps that go away when subsequent sub-phases
# replace Castor (B.3, DR-006), Spring 5→6 (B.4) and Hibernate 5→6 (B.5).
#
# Phase B.4 + B.6: Tomcat 9 → 10 — required for the jakarta.servlet 6 WAR.
# Castor was retired in B.3 (DR-006), so the java.xml export is now dead
# weight; left in place for one more cycle to keep diff scope tight.
# Phase C.14 cliff (2026-05-30): -Dorg.springframework.boot.logging.LoggingSystem=none
# tells Boot to skip its LogbackLoggingSystem and let logback init via its own
# auto-discovery. Required because logback.xml's 10 RollingFileAppenders share
# a file pattern (intentional facility-by-encoder structure); Boot 3.5's
# LogbackLoggingSystem.initialize reads StatusManager and throws on any ERROR.
ENV CATALINA_OPTS="\
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED \
    --add-exports=java.naming/com.sun.jndi.ldap=ALL-UNNAMED \
    -Dorg.springframework.boot.logging.LoggingSystem=none"

LABEL org.opencontainers.image.title="LibreClinica MUW Ophthalmology"
LABEL org.opencontainers.image.description="Electronic Data Capture for the Department of Ophthalmology and Optometry, Medical University of Vienna — institutional build of LibreClinica."
LABEL org.opencontainers.image.vendor="Department of Ophthalmology and Optometry, Medical University of Vienna"
LABEL org.opencontainers.image.licenses="LGPL-2.1-or-later"
LABEL org.opencontainers.image.source="https://github.com/LuViKu/LibreClinicaMUW"

RUN set -eux; \
    # set up redirection to application when accessing tomcat root
    mkdir /usr/local/tomcat/webapps/ROOT; \
    printf '%s\n' \
        '<!DOCTYPE html>' \
        '<html lang="en">' \
        '<head>' \
        '  <meta charset="utf-8" />' \
        '  <meta http-equiv="refresh" content="0; URL=LibreClinica/" />' \
        '  <title>LibreClinica MUW Ophthalmology</title>' \
        '</head>' \
        '<body>' \
        '  <p>Loading LibreClinica MUW Ophthalmology &mdash; Department of Ophthalmology and Optometry, Medical University of Vienna.</p>' \
        '  <p>If you are not redirected automatically, <a href="LibreClinica/">click here</a>.</p>' \
        '</body>' \
        '</html>' \
        > /usr/local/tomcat/webapps/ROOT/index.html;

# set up volumes for data and logs
VOLUME \
    /usr/local/tomcat/libreclinica.data \
    /usr/local/tomcat/logs

# add config files
COPY \
    /docker/config/ \
    /usr/local/tomcat/libreclinica.config/

# add libre-clinica war file
COPY --from=builder \
    /LibreClinica-web.war \
    /usr/local/tomcat/webapps/LibreClinica.war

# Phase B/A7 hardening (2026-06): healthcheck so `docker compose up` and the
# cluster orchestrator can block on the WAR actually serving rather than the
# JVM merely being up. Boot's actuator/health endpoint returns {"status":"UP"}
# only after Liquibase has applied pending changesets AND the Spring context
# has finished refreshing (Quartz has @DependsOn("liquibase") in
# core/.../config/QuartzConfig.java:63, so Quartz init is in the gate too).
#
# Timing budget for start-period: Liquibase changelog apply ~20-30 s on a
# steady-state schema, Quartz scheduler init ~5 s, Tomcat WAR deploy
# ~30-40 s. 90 s gives the orchestrator a clean signal without false
# negatives on a cold start. interval/timeout/retries match the
# retinal-inference sidecar (retinal-inference/Dockerfile:12-13).
#
# curl is preinstalled in the tomcat:10-jdk21 (Debian) base — verified
# 2026-06-10 — so no apt-get layer needed.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/LibreClinica/actuator/health || exit 1
