package nada;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NadaApplicationTest {

    @Test
    void usesDefaultPortWhenNothingIsConfigured() {
        assertEquals(Limits.DEFAULT_PORT, NadaApplication.resolvePort(new String[0], null));
        assertEquals(Limits.DEFAULT_PORT, NadaApplication.resolvePort(new String[]{"  "}, "  "));
    }

    @Test
    void argumentOverridesEnvironmentAndEnvironmentOverridesDefault() {
        assertEquals(9090, NadaApplication.resolvePort(new String[]{"9090"}, "7000"));
        assertEquals(7000, NadaApplication.resolvePort(new String[0], "7000"));
    }

    @Test
    void rejectsPortsOutsideTheValidRange() {
        assertThrows(IllegalArgumentException.class, () -> NadaApplication.resolvePort(new String[]{"-1"}, null));
        assertThrows(IllegalArgumentException.class, () -> NadaApplication.resolvePort(new String[]{"65536"}, null));
        assertThrows(IllegalArgumentException.class, () -> NadaApplication.resolvePort(new String[]{"http"}, null));
    }
}
