package example;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

public final class CustomerDirectory {

    private final Map<String, Customer> byId = new ConcurrentHashMap<>();

    public void add(Customer customer) {
        byId.put(customer.id(), customer);
    }

    /** Absence is part of the answer, so the return type says so; callers cannot forget the empty case. */
    public Optional<Customer> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** For hot paths and Spring-style APIs: @Nullable instead of Optional, checked by NullAway at each call. */
    public @Nullable Customer findOrNull(String id) {
        return byId.get(id);
    }

    public String greeting(String id) {
        Customer customer = findOrNull(id);
        if (customer == null) {
            return "Hello, guest";
        }
        return "Hello, " + customer.displayName();
    }

    /** Collections are empty, never null. */
    public List<Customer> withNickname() {
        return byId.values().stream().filter(c -> c.nickname() != null).toList();
    }
}
