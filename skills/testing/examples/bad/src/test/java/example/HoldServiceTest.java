package example;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// MISTAKE: an in-memory H2 URL replaces the real database for the whole test run.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL")
// MISTAKE: the second test needs the row the first one inserted; run it alone or in parallel and it fails.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HoldServiceTest {

    @Autowired ReservationRepository reservations;

    @Test
    @Order(1)
    void holdsAGuest() {
        Mailer mailer = mock(Mailer.class);
        Instant expiresAt = new HoldService(reservations, mailer).hold("ana@example.com");
        // MISTAKE: asserting against a second Instant.now() fails when the clock ticks between the two calls.
        assertEquals(Instant.now().plus(HoldService.HOLD), expiresAt);
    }

    @Test
    @Order(2)
    void releasesTheHoldLater() throws InterruptedException {
        Mailer mailer = mock(Mailer.class);
        // MISTAKE: sleeping for real time; HOLD would mean a 15-minute test, so someone shortens it and it flakes on a slow CI runner.
        Thread.sleep(Duration.ofSeconds(2));
        assertEquals(0, new HoldService(reservations, mailer).releaseExpired());
    }
}
