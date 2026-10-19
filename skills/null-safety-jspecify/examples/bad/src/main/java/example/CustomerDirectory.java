package example;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class CustomerDirectory {

    private final Map<String, Customer> byId = new ConcurrentHashMap<>();

    // MISTAKE: Optional as a field: an extra allocation per object, not Serializable, and it can itself be null.
    private Optional<Customer> lastAdded = Optional.empty();

    // MISTAKE: Optional as a parameter: callers now pass null, Optional.empty() or a value.
    public void add(Customer customer, Optional<String> source) {
        byId.put(customer.id(), customer);
        lastAdded = Optional.of(customer);
    }

    // MISTAKE: the return type is non-null under @NullMarked, yet Map.get returns null for a missing id.
    public Customer find(String id) {
        return byId.get(id);
    }

    // MISTAKE: the deprecated Spring JSR 305 annotation; Spring Framework 7 and NullAway read JSpecify's.
    @org.springframework.lang.Nullable
    public List<Customer> withNickname() {
        // MISTAKE: a null collection instead of an empty one makes every caller add a null check.
        return byId.isEmpty() ? null : List.copyOf(byId.values());
    }
}
