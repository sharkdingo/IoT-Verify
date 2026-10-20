package cn.edu.nju.Iot_Verify.controller;

import cn.edu.nju.Iot_Verify.security.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every endpoint addressed by a client-supplied identifier must receive the caller's identity.
 *
 * <p>This is the structural half of the tenancy boundary. A live cross-account probe drove all 40 reachable
 * ID-addressed endpoints with a second account's token and got 38 hard refusals plus 2 correctly scoped-empty
 * collections — zero breaches. But that probe is an audit artifact: it is not in the suite, it needs a running
 * backend and NuSMV, and it can only test the endpoints that existed when it was written.
 *
 * <p>What actually keeps the boundary closed is a habit: a path or query parameter never selects a row on its own, only
 * ever in combination with the authenticated user. A new endpoint that forgets {@code @CurrentUser} is one line
 * of plausible-looking code, it compiles, it works perfectly in manual testing with one account, and nothing
 * fails. This test is what fails.
 *
 * <p>Deliberately reflective rather than a request-level test. It cannot be satisfied by adding a mock, it
 * covers endpoints written after it, and it states the rule in the place a reader looks for the rule. The
 * live probe proves the current implementation is correct; this proves the next one has to be.
 *
 * <p>Scope note: receiving the identity is necessary, not sufficient — a controller could accept
 * {@code @CurrentUser} and then ignore it. That residual risk is covered from the other side, by the
 * repository layer having no bare {@code findById} finder for any user-owned entity, and by the cross-account
 * probe. Three overlapping checks, none of which is load-bearing alone.
 */
class OwnershipBoundaryContractTest {

    /** Every controller in the application. Listed explicitly so a new one is a deliberate addition here. */
    private static final List<Class<?>> CONTROLLERS = List.of(
            AuthController.class,
            BoardStorageController.class,
            ChatController.class,
            FuzzController.class,
            SimulationController.class,
            VerificationController.class
    );

    /**
     * Endpoints that legitimately serve an anonymous caller, with the reason each one is safe.
     *
     * <p>Register and login *establish* identity, so requiring it would be circular. The template schema is a
     * static JSON document describing the device-template format — it contains no user data and is identical
     * for every caller. Nothing else belongs here, and adding an entry should feel like a decision.
     */
    private static final Set<String> INTENTIONALLY_PUBLIC = Set.of(
            "POST /api/auth/register",
            "POST /api/auth/login",
            "GET /api/board/templates/schema"
    );

    private static final List<Class<? extends Annotation>> MAPPINGS = List.of(
            GetMapping.class, PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class);

    @Test
    @DisplayName("every endpoint addressed by an identifier also receives the caller identity")
    void idAddressedEndpointsReceiveTheCaller() {
        List<String> offenders = new ArrayList<>();
        int idAddressed = 0;

        for (Class<?> controller : CONTROLLERS) {
            String prefix = prefixOf(controller);
            for (Method method : controller.getDeclaredMethods()) {
                String route = routeOf(method, prefix);
                if (route == null) continue;
                // Query parameters can select owned records too; moving an id out of the path must not
                // remove the endpoint from this boundary check. Query filters are included conservatively.
                boolean addressedByClient = route.contains("{");
                for (Parameter parameter : method.getParameters()) {
                    addressedByClient |= parameter.isAnnotationPresent(RequestParam.class);
                }
                if (!addressedByClient) continue;
                idAddressed++;
                if (INTENTIONALLY_PUBLIC.contains(route)) continue;
                if (!receivesCaller(method)) {
                    offenders.add(route + "  (" + controller.getSimpleName() + "." + method.getName() + ")");
                }
            }
        }

        // A count assertion as well as an empty-offenders one: if the route parsing ever silently stops
        // matching, "no offenders" would be vacuously true and this test would pass while checking nothing.
        assertTrue(idAddressed >= 40,
                "expected at least 40 ID-addressed endpoints, found " + idAddressed
                        + " — the route scan is probably broken, so an empty offender list proves nothing");
        assertEquals(List.of(), offenders,
                "these endpoints select a row by a client-supplied id without receiving the caller identity");
    }

    @Test
    @DisplayName("the public-endpoint allowlist stays minimal and every entry still exists")
    void publicAllowlistIsHonest() {
        // An allowlist that outlives its entries is how an exemption becomes permanent by accident: the route is
        // renamed, the entry stops matching anything, and it sits there looking like a considered decision.
        List<String> allRoutes = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            String prefix = prefixOf(controller);
            for (Method method : controller.getDeclaredMethods()) {
                String route = routeOf(method, prefix);
                if (route != null) allRoutes.add(route);
            }
        }

        for (String exempt : INTENTIONALLY_PUBLIC) {
            assertTrue(allRoutes.contains(exempt),
                    "'" + exempt + "' is exempted from the ownership rule but no longer exists — remove the entry");
        }
        assertTrue(INTENTIONALLY_PUBLIC.size() <= 3,
                "the anonymous-endpoint allowlist grew to " + INTENTIONALLY_PUBLIC.size()
                        + "; each addition needs a stated reason in the field's javadoc");
    }

    private static boolean receivesCaller(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(CurrentUser.class)) return true;
        }
        return false;
    }

    private static String prefixOf(Class<?> controller) {
        RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
        if (mapping == null || mapping.value().length == 0) return "";
        return mapping.value()[0];
    }

    /** The "VERB /path" this method serves, or null when it is not an endpoint. */
    private static String routeOf(Method method, String prefix) {
        for (Class<? extends Annotation> type : MAPPINGS) {
            Annotation annotation = method.getAnnotation(type);
            if (annotation == null) continue;
            String verb = type.getSimpleName().replace("Mapping", "").toUpperCase();
            String suffix = firstPath(annotation);
            String path = (prefix + suffix).replaceAll("/{2,}", "/");
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return verb + " " + path;
        }
        return null;
    }

    private static String firstPath(Annotation annotation) {
        try {
            String[] value = (String[]) annotation.annotationType().getMethod("value").invoke(annotation);
            return value.length == 0 ? "" : value[0];
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read the path from " + annotation, e);
        }
    }
}
