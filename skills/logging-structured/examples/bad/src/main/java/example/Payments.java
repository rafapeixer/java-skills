package example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class Payments {

    private static final Logger log = LoggerFactory.getLogger(Payments.class);

    // MISTAKE: the record's generated toString prints every component, so any log line that
    // includes a Card writes the full card number and the holder's name.
    public record Card(String number, String holder) {}

    static final class DeclinedException extends RuntimeException {
        DeclinedException(String reason) {
            super(reason);
        }
    }

    public boolean charge(String requestId, String orderId, Card card, long amountCents) {
        // MISTAKE: put without remove. The next task on this thread (a pooled platform thread,
        // an executor worker) logs with this request's id.
        MDC.put("requestId", requestId);
        // MISTAKE: System.out bypasses the logging system: no level, no JSON, no MDC.
        System.out.println("charging order " + orderId);
        try {
            authorize(card, amountCents);
        } catch (DeclinedException e) {
            // MISTAKE: a client-side outcome logged as ERROR with a stack trace; alerts fire
            // on every declined card. And it is the second log line for the same event.
            log.error("payment failed", e);
            return false;
        }
        // MISTAKE: concatenation builds the string even when INFO is off, and the order id and
        // amount end up inside the message text instead of as queryable fields.
        log.info("payment authorized for order " + orderId + " amount " + amountCents + " card " + card);
        return true;
    }

    private void authorize(Card card, long amountCents) {
        if (amountCents > 100_000) {
            DeclinedException e = new DeclinedException("over card limit");
            // MISTAKE: log and rethrow. The caller logs it again, so one decline is two
            // ERROR lines with two stack traces, and this one prints the card number.
            log.error("authorization declined for " + card, e);
            throw e;
        }
    }
}
