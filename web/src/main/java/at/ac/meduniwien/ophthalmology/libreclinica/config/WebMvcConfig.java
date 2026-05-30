package at.ac.meduniwien.ophthalmology.libreclinica.config;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.xml.MarshallingHttpMessageConverter;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping;
import org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter;
import org.springframework.web.servlet.mvc.UrlFilenameViewController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.SidebarEnumConstants;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.SidebarInit;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.helper.SetUpUserInterceptor;

/**
 * Phase C.11 (2026-05-30): Java replacement for {@code pages-servlet.xml}.
 * <p>
 * Loaded by the {@code pages} {@link org.springframework.web.servlet.DispatcherServlet}
 * registered in {@code web.xml} — its child context bootstraps this
 * configuration via the (now-empty-of-beans) {@code pages-servlet.xml}
 * which delegates to this class via {@code <bean class="WebMvcConfig"/>}.
 * <p>
 * Bean shape preserved verbatim:
 * <ul>
 *   <li>{@code /login/login} + {@code /denied} {@link UrlFilenameViewController}s
 *       (URL path substring becomes view name)</li>
 *   <li>{@link SimpleControllerHandlerAdapter} for the URL controllers above</li>
 *   <li>Component-scan of {@code .controller} package — picks up all
 *       {@code @Controller} classes</li>
 *   <li>{@link SidebarInit} (alerts/icons/info/instructions box defaults)</li>
 *   <li>{@link SetUpUserInterceptor} (loads {@code currentUser} into session)</li>
 *   <li>JAXB marshaller for CDISC ODM (xml) + Jackson converter (json) +
 *       string converter</li>
 *   <li>{@link RequestMappingHandlerMapping} + {@link RequestMappingHandlerAdapter}
 *       with explicit message-converter list</li>
 *   <li>{@link BeanNameUrlHandlerMapping} (for the {@code /login/login} bean
 *       name route above)</li>
 *   <li>{@link InternalResourceViewResolver} JSP resolver
 *       ({@code /WEB-INF/jsp/} prefix, {@code .jsp} suffix)</li>
 * </ul>
 * <p>
 * The legacy XML's commented-out {@code <mvc:annotation-driven />} stays
 * commented-out: the manual {@code RequestMappingHandlerMapping} +
 * {@code RequestMappingHandlerAdapter} below give us control over the
 * message-converter list (XML/JSON for the rules REST API), which
 * {@code @EnableWebMvc} doesn't.
 */
@Configuration
@ComponentScan("at.ac.meduniwien.ophthalmology.libreclinica.controller")
public class WebMvcConfig {

    @Bean(name = "/login/login")
    public UrlFilenameViewController loginLoginController() {
        return new UrlFilenameViewController();
    }

    @Bean(name = "/denied")
    public UrlFilenameViewController deniedController() {
        return new UrlFilenameViewController();
    }

    @Bean
    public SimpleControllerHandlerAdapter simpleControllerHandlerAdapter() {
        return new SimpleControllerHandlerAdapter();
    }

    @Bean
    public SidebarInit sidebarInit() {
        SidebarInit s = new SidebarInit();
        s.setAlertsBoxSetup(SidebarEnumConstants.OPENALERTS);
        s.setEnableIconsBoxSetup(SidebarEnumConstants.DISABLEICONS);
        s.setInfoBoxSetup(SidebarEnumConstants.OPENINFO);
        s.setInstructionsBoxSetup(SidebarEnumConstants.OPENINSTRUCTIONS);
        return s;
    }

    @Bean
    public SetUpUserInterceptor setUpUserInterceptor(@Qualifier("dataSource") DataSource dataSource) {
        SetUpUserInterceptor i = new SetUpUserInterceptor();
        i.setDataSource(dataSource);
        return i;
    }

    @Bean
    public Jaxb2Marshaller jaxbMarshaller() {
        Jaxb2Marshaller m = new Jaxb2Marshaller();
        m.setContextPaths(
                "org.cdisc.ns.odm.v130",
                "org.cdisc.ns.odm.v130_api",
                "org.openclinica.ns.odm_ext_v130.v31_api",
                "org.openclinica.ns.odm_ext_v130.v31",
                "org.openclinica.ns.rules.v31",
                "org.openclinica.ns.response.v31",
                "org.openclinica.ns.rules_test.v31");
        return m;
    }

    @Bean
    public MarshallingHttpMessageConverter marshallingHttpMessageConverter(Jaxb2Marshaller jaxbMarshaller) {
        MarshallingHttpMessageConverter mc = new MarshallingHttpMessageConverter();
        mc.setMarshaller(jaxbMarshaller);
        mc.setUnmarshaller(jaxbMarshaller);
        mc.setSupportedMediaTypes(List.of(MediaType.APPLICATION_XML));
        return mc;
    }

    @Bean
    public StringHttpMessageConverter stringHttpMessageConverter() {
        return new StringHttpMessageConverter();
    }

    @Bean
    public MappingJackson2HttpMessageConverter jacksonMessageConverter() {
        MappingJackson2HttpMessageConverter mc = new MappingJackson2HttpMessageConverter();
        mc.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON));
        return mc;
    }

    @Bean
    public RequestMappingHandlerMapping requestMappingHandlerMapping() {
        return new RequestMappingHandlerMapping();
    }

    @Bean
    public RequestMappingHandlerAdapter requestMappingHandlerAdapter(
            MarshallingHttpMessageConverter marshallingHttpMessageConverter,
            MappingJackson2HttpMessageConverter jacksonMessageConverter) {
        RequestMappingHandlerAdapter a = new RequestMappingHandlerAdapter();
        a.setMessageConverters(List.of(marshallingHttpMessageConverter, jacksonMessageConverter));
        return a;
    }

    @Bean
    public BeanNameUrlHandlerMapping beanNameUrlHandlerMapping() {
        return new BeanNameUrlHandlerMapping();
    }

    @Bean
    public InternalResourceViewResolver internalViewResolver() {
        InternalResourceViewResolver r = new InternalResourceViewResolver();
        r.setPrefix("/WEB-INF/jsp/");
        r.setSuffix(".jsp");
        return r;
    }
}
