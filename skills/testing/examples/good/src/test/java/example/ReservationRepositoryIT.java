package example;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Integration test: the real SQL against the real PostgreSQL version, run by failsafe. */
@SpringBootTest
@Testcontainers
class ReservationRepositoryIT {

    // Static: one container for the class. @ServiceConnection points spring.datasource at it.
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @Autowired ReservationRepository reservations;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void emptyTable() {
        jdbc.sql("delete from reservation").update();
    }

    @Test
    void deletesOnlyHoldsThatExpiredAtOrBeforeNow() {
        reservations.insert("late@example.com", NOW.minusSeconds(1));
        reservations.insert("edge@example.com", NOW);
        reservations.insert("early@example.com", NOW.plusSeconds(1));

        assertEquals(List.of("edge@example.com", "late@example.com"),
                reservations.deleteExpired(NOW).stream().sorted().toList());
        assertEquals(1, jdbc.sql("select count(*) from reservation").query(Long.class).single());
    }

    @Test
    void aHoldMadeNowIsReleasedFifteenMinutesLater() {
        Mailer mailer = mock(Mailer.class);
        new HoldService(reservations, mailer, Clock.fixed(NOW, ZoneOffset.UTC)).hold("ana@example.com");

        var justBefore = Clock.fixed(NOW.plus(HoldService.HOLD).minusMillis(1), ZoneOffset.UTC);
        assertEquals(0, new HoldService(reservations, mailer, justBefore).releaseExpired());
        var atExpiry = Clock.fixed(NOW.plus(HoldService.HOLD), ZoneOffset.UTC);
        assertEquals(1, new HoldService(reservations, mailer, atExpiry).releaseExpired());
        verify(mailer).send("ana@example.com", "Your hold has expired.");
    }
}
