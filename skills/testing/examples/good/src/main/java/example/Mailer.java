package example;

import org.springframework.stereotype.Component;

/** Talks to the mail provider over the network. Final, as most real clients are. */
@Component
public final class Mailer {

    public void send(String to, String text) {
        throw new UnsupportedOperationException("calls the mail provider");
    }
}
