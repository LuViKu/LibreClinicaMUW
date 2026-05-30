/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Phase C.14 cliff (2026-05-30): pure {@link ServletContextListener} doing
 * MDC + hostname setup only. Was previously a subclass of Spring's
 * {@code ContextLoaderListener} — that responsibility now belongs to
 * {@code SpringBootServletInitializer}. Registered via
 * {@code @Bean ServletListenerRegistrationBean} in
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.config.ServletInfraConfig}.
 */
public class OCContextLoaderListener implements ServletContextListener {
    private final Logger logger = LoggerFactory.getLogger(this.getClass().getName());


    @Override
    public void contextInitialized(ServletContextEvent event) {
        String path = event.getServletContext().getRealPath("/");
        String webAppName = getWebAppName(path);

        // Put the web application name into the logging context. This value is
        // used inside the logback.xml
        MDC.put("WEBAPP", webAppName);
        // @pgawade 18-July-2011: Get hostname to send it through usage
        // statistics information
        String hostName = "";
        try {
            hostName = getHostName();
        } catch (UnknownHostException uhe) {
            logger.error("UnknownHostException when fetching the hostname");
        }
        MDC.put("HOSTNAME", hostName);
    }

    public String getWebAppName(String servletCtxRealPath) {
        String webAppName = null;
        if (null != servletCtxRealPath) {
            String[] tokens = servletCtxRealPath.split("\\\\");
            webAppName = tokens[(tokens.length - 1)].trim();
        }
        return webAppName;
    }

    // @pgawade 18-July-2011
    public String getHostName() throws UnknownHostException {
        InetAddress addr = InetAddress.getLocalHost();
        String cHostName = addr.getCanonicalHostName();
        return cHostName;
    }
}
