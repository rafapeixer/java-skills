import com.example.shop.model.Order;
import com.example.shop.repository.Store;
import com.example.shop.service.BillingServiceImpl;
import com.example.shop.service.OrderService;
import com.example.shop.service.OrderServiceImpl;
import com.example.shop.util.MoneyUtils;

// MISTAKE: the entry point is in the default package; it cannot be imported, and a
// @SpringBootApplication here makes component scanning read every class in every jar.
public class Main {
    public static void main(String[] args) {
        Store store = new Store();
        OrderService orders = new OrderServiceImpl(new BillingServiceImpl(store), store);
        long cents = 2_500;
        // MISTAKE: a business rule (the discount) written in main, where no test reaches it.
        if (cents > 2_000) {
            cents = MoneyUtils.applyDiscount(cents, 10);
        }
        Order order = orders.place("cus_1", cents);
        System.out.println("order " + order.id() + " charged as " + order.chargeId());
    }
}
