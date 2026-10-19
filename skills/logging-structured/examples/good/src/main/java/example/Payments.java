package example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class Payments {

    private static final Logger log = LoggerFactory.getLogger(Payments.class);

    /** toString never prints the number, so passing a Card to a logger cannot leak it. */
    public record Card(String number, String holder) {
        public String last4() {
            return number.substring(number.length() - 4);
        }

        @Override
        public String toString() {
            return "Card[last4=" + last4() + "]";
        }
    }

    static final class DeclinedException extends RuntimeException {
        DeclinedException(String reason) {
            super(reason);
        }
    }

    /** The boundary of one unit of work: it sets the request id and logs the outcome once. */
    public boolean charge(String requestId, String orderId, Card card, long amountCents) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("request.id", requestId)) {
            try {
                authorize(card, amountCents);
            } catch (DeclinedException e) {
                log.atWarn()
                        .setMessage("payment declined")
                        .addKeyValue("order.id", orderId)
                        .addKeyValue("decline.reason", e.getMessage())
                        .log();
                return false;
            }
            log.atInfo()
                    .setMessage("payment authorized")
                    .addKeyValue("order.id", orderId)
                    .addKeyValue("amount.cents", amountCents)
                    .addKeyValue("card.last4", card.last4())
                    .log();
            return true;
        }
    }

    private void authorize(Card card, long amountCents) {
        // Placeholders: the message is only built if DEBUG is on for this logger.
        log.debug("authorizing {} cents on {}", amountCents, card);
        if (amountCents > 100_000) {
            throw new DeclinedException("over card limit"); // thrown, not logged: charge() logs it
        }
    }
}
