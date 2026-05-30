# LibreClinica MUW Ophthalmology — Apache + mod_shib SP sidecar
# Department of Ophthalmology and Optometry, Medical University of Vienna
#
# Phase D.7 (DR-014): reference Shibboleth SP sidecar for the
# institution-agnostic reverse-proxy SSO architecture. Terminates SAML
# in front of Tomcat; populates REMOTE_USER + SAML attributes as HTTP
# request headers; proxies authenticated requests to libreclinica:8080.
#
# Default IdP: SAMLtest.id (https://samltest.id) — public free test
# IdP. See docker/sso/README.md for the one-time SP-metadata upload
# step needed to make SAMLtest.id trust your SP.
#
# Production swap: change the entityID in shibboleth2.xml + register
# the SP with MedUni Wien IT (login.meduniwien.ac.at SAML IdP).

FROM debian:bookworm-slim

# Install Apache 2.4 + Shibboleth SP module from Debian's main repo.
# The packaged version follows Shibboleth upstream stable; for an
# institutional production deployment, pin via the SWITCH repo
# (https://swdistrib.uni-mainz.de/) which tracks security releases.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        apache2 \
        libapache2-mod-shib \
        ca-certificates \
        curl && \
    rm -rf /var/lib/apt/lists/*

# Enable the Apache modules mod_shib needs.
RUN a2enmod ssl proxy proxy_http shib headers

# Drop the default vhost; bring in ours.
RUN rm -f /etc/apache2/sites-enabled/000-default.conf
COPY apache-shib-vhost.conf /etc/apache2/sites-enabled/000-default.conf

# Shibboleth SP config files — institution-specific overrides go in
# these three files. Default config targets SAMLtest.id; production
# operators replace shibboleth2.xml with their MedUni Wien-registered
# SP config (entityID + cert pinning + IdP metadata URL).
COPY shibboleth2.xml /etc/shibboleth/shibboleth2.xml
COPY attribute-map.xml /etc/shibboleth/attribute-map.xml

# Shibboleth daemon listens on its UNIX socket; Apache mod_shib talks
# to it over that socket. Both processes run in the same container.
EXPOSE 80 443

# Entry point: start shibd in the background, then apache in the
# foreground (so the container lives as long as Apache does).
RUN echo '#!/bin/sh\n\
set -e\n\
shibd -f -F &\n\
exec apache2ctl -D FOREGROUND' > /usr/local/bin/start.sh && \
    chmod +x /usr/local/bin/start.sh

CMD ["/usr/local/bin/start.sh"]
