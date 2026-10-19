package example;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class HoldService {

    static final Duration HOLD = Duration.ofMinutes(15);

    private final ReservationRepository reservations;
    private final Mailer mailer;

    public HoldService(ReservationRepository reservations, Mailer mailer) {
        this.reservations = reservations;
        this.mailer = mailer;
    }

    public Instant hold(String guest) {
        // MISTAKE: reads the wall clock directly, so a test can only check expiry by waiting for it.
        Instant expiresAt = Instant.now().plus(HOLD);
        reservations.insert(guest, expiresAt);
        return expiresAt;
    }

    public int releaseExpired() {
        var released = reservations.deleteExpired(Instant.now());
        released.forEach(guest -> mailer.send(guest, "Your hold has expired."));
        return released.size();
    }
}
