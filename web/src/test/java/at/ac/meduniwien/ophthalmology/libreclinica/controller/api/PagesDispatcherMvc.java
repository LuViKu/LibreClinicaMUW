/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.List;

import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

import at.ac.meduniwien.ophthalmology.libreclinica.webmvc.WebMvcConfig;

/**
 * MockMvc on the message converters of the {@code pages} dispatcher, taken
 * from the {@code requestMappingHandlerAdapter} bean method that wires it,
 * not from MockMvc's defaults (which include a String and a Resource converter
 * the dispatcher does not have).
 *
 * <p>Deliberately free of other test helpers so a regression test using it can
 * move between branches unchanged.
 */
final class PagesDispatcherMvc {

    private PagesDispatcherMvc() {}

    static StandaloneMockMvcBuilder standalone(Object... controllers) {
        WebMvcConfig cfg = new WebMvcConfig();
        List<HttpMessageConverter<?>> converters = cfg
                .requestMappingHandlerAdapter(
                        cfg.marshallingHttpMessageConverter(cfg.jaxbMarshaller()),
                        cfg.jacksonMessageConverter())
                .getMessageConverters();
        return MockMvcBuilders.standaloneSetup(controllers)
                .setMessageConverters(converters.toArray(new HttpMessageConverter<?>[0]));
    }
}
