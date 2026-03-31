package benchmark.model;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class OrderStore {
    private static final OrderStore INSTANCE = new OrderStore();
    private final ConcurrentHashMap<String, OrderResponse> store = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(0);

    private OrderStore() {}

    public static OrderStore getInstance() {
        return INSTANCE;
    }

    public long nextId() {
        return counter.incrementAndGet();
    }

    public void put(String userId, String orderId, OrderResponse order) {
        store.put(userId + ":" + orderId, order);
    }

    public OrderResponse get(String userId, String orderId) {
        return store.get(userId + ":" + orderId);
    }

    public OrderResponse remove(String userId, String orderId) {
        return store.remove(userId + ":" + orderId);
    }
}
