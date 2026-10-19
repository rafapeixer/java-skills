package example;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit test: no Spring, no Docker, no sleeping. Time is a fixed Clock, so every run sees the same instant. */
@ExtendWith(MockitoExtension.class)
class HoldServiceTest {

    static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @Mock ReservationRepository reservations;
    @Mock Mailer mailer;

    HoldService service() {
        return new HoldService(reservations, mailer, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aHoldExpiresFifteenMinutesFromNow() {
        assertEquals(Instant.parse("2026-10-18T12:15:00Z"), service().hold("ana@example.com"));
        verify(reservations).insert("ana@example.com", Instant.parse("2026-10-18T12:15:00Z"));
    }

    @Test
    void releasingExpiredHoldsMailsEachGuestOnce() {
        when(reservations.deleteExpired(NOW)).thenReturn(List.of("ana@example.com", "bo@example.com"));
        assertEquals(2, service().releaseExpired());
        verify(mailer).send("ana@example.com", "Your hold has expired.");
        verify(mailer).send("bo@example.com", "Your hold has expired.");
        verifyNoMoreInteractions(mailer);
    }

    @Test
    void mockitoIsLoadedAsAnAgentNotAttachedAtRuntime() {
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertTrue(jvmArgs.stream().anyMatch(a -> a.startsWith("-javaagent:") && a.contains("mockito-core")), jvmArgs::toString);
    }
}
