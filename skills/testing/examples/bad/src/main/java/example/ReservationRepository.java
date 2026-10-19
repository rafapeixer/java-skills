package example;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private final JdbcClient jdbc;

    public ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String guest, Instant expiresAt) {
        return jdbc.sql("insert into reservation (guest, expires_at) values (?, ?) returning id")
                .params(guest, Timestamp.from(expiresAt))
                .query(Long.class)
                .single();
    }

    /** Deletes reservations whose expiry is at or before {@code now}; returns the guests released. */
    public List<String> deleteExpired(Instant now) {
        return jdbc.sql("delete from reservation where expires_at <= ? returning guest")
                .param(Timestamp.from(now))
                .query(String.class)
                .list();
    }
}
