package in.stockline.observability;

import java.util.UUID;

/** Request correlation state propagated to transactional outbox writes. */
public final class CorrelationContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {}

    public static UUID current() { return CURRENT.get(); }

    public static UUID replace(UUID correlationId) {
        UUID previous = CURRENT.get();
        if (correlationId == null) CURRENT.remove(); else CURRENT.set(correlationId);
        return previous;
    }

    public static void restore(UUID correlationId) { replace(correlationId); }
}
