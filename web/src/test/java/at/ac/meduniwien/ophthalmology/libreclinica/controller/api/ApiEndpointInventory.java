/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every request-mapped handler in {@code controller/api}, found by reflection,
 * so the cross-site isolation matrix cannot silently skip an endpoint that is
 * added later: {@code CrossSiteIsolationDatabaseIT#everyEndpointIsInTheMatrixOrExplicitlyOutOfScope}
 * fails on a handler that is neither in the matrix nor in the out-of-scope list.
 */
final class ApiEndpointInventory {

    private ApiEndpointInventory() {}

    /** One handler method: {@code Controller#method}, its verbs and its full path. */
    static final class Endpoint {
        final Class<?> controller;
        final Method method;
        final String key;
        final String verbs;
        final String path;
        /** Names of the path variables / request params, as {@code P:name} / {@code R:name} / {@code B}. */
        final List<String> inputs = new ArrayList<>();

        Endpoint(Class<?> controller, Method method, String verbs, String path) {
            this.controller = controller;
            this.method = method;
            this.key = controller.getSimpleName() + "#" + method.getName();
            this.verbs = verbs;
            this.path = path;
        }

        /** True when the handler takes anything that can name a record. */
        boolean takesIdentifier() {
            return !inputs.isEmpty();
        }

        @Override
        public String toString() {
            return verbs + " " + path + "  [" + key + "]  " + inputs;
        }
    }

    private static final Pattern IDISH = Pattern.compile(
            "(?i).*(id|oid|label|uuid|token|sha256|name|q|search|subject|patient|study|site|event|job|file|dataset|note).*");

    static List<Endpoint> all() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Controller.class));
        List<Endpoint> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(
                "at.ac.meduniwien.ophthalmology.libreclinica.controller.api")) {
            Class<?> cls = Class.forName(bd.getBeanClassName());
            // Controllers that only exist in test sources (exception-handler fixtures) are not endpoints.
            if (String.valueOf(cls.getProtectionDomain().getCodeSource().getLocation()).contains("test-classes")) continue;
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(cls, RequestMapping.class);
            String base = classMapping == null || classMapping.path().length == 0 ? "" : classMapping.path()[0];
            for (Method m : cls.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                String sub = rm.path().length == 0 ? "" : rm.path()[0];
                String verbs = rm.method().length == 0 ? "ANY"
                        : String.join("|", Arrays.stream(rm.method()).map(RequestMethod::name).toList());
                Endpoint e = new Endpoint(cls, m, verbs, normalise(base + sub));
                for (Parameter p : m.getParameters()) {
                    PathVariable pv = p.getAnnotation(PathVariable.class);
                    RequestParam rp = p.getAnnotation(RequestParam.class);
                    if (pv != null) {
                        e.inputs.add("P:" + (pv.value().isEmpty() ? p.getName() : pv.value()));
                    } else if (rp != null) {
                        e.inputs.add("R:" + (rp.value().isEmpty() ? p.getName() : rp.value()));
                    } else if (p.isAnnotationPresent(RequestBody.class)) {
                        e.inputs.add("B");
                    } else if (org.springframework.web.multipart.MultipartFile.class.isAssignableFrom(p.getType())) {
                        e.inputs.add("F");
                    }
                }
                out.add(e);
            }
        }
        out.sort(Comparator.comparing((Endpoint e) -> e.key));
        return out;
    }

    /** {@code {id:[0-9]+}} becomes {@code {id}} so a path reads the same wherever it was declared. */
    static String normalise(String path) {
        return path.replaceAll("\\{([A-Za-z0-9_]+):[^}]*}", "{$1}");
    }

    static Set<String> keys(List<Endpoint> endpoints) {
        Set<String> keys = new TreeSet<>();
        for (Endpoint e : endpoints) keys.add(e.key);
        return keys;
    }

    static boolean idLike(String input) {
        return input.equals("B") || input.equals("F") || IDISH.matcher(input.substring(2)).matches();
    }
}
