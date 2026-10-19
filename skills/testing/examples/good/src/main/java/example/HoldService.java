package example;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class HoldService {

    static final Duration HOLD = Duration.ofMinutes(15);

    private final ReservationRepository reservations;
    private final Mailer mailer;
    private final Clock clock;

    public HoldService(ReservationRepository reservations, Mailer mailer, Clock clock) {
        this.reservations = reservations;
        this.mailer = mailer;
        this.clock = clock;
    }

    public Instant hold(String guest) {
        Instant expiresAt = clock.instant().plus(HOLD);
        reservations.insert(guest, expiresAt);
        return expiresAt;
    }

    public int releaseExpired() {
        var released = reservations.deleteExpired(clock.instant());
        released.forEach(guest -> mailer.send(guest, "Your hold has expired."));
        return released.size();
    }
}
