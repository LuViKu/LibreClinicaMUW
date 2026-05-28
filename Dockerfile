FROM docker.io/library/maven:3-eclipse-temurin-8 AS builder

WORKDIR /app

COPY . .

RUN \
    # cache downloaded dependencies
    --mount=type=cache,target=/root/.m2 \
    # build cache
    --mount=type=cache,target=/app/core/target \
    --mount=type=cache,target=/app/docs/target \
    --mount=type=cache,target=/app/odm/target \
    --mount=type=cache,target=/app/web/target \
    --mount=type=cache,target=/app/ws/target \

    set -eux; \
    mvn package; \
    mv web/target/LibreClinica-web.war /;

############################################################
FROM tomcat:9-jdk11

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
