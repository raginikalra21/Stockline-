package in.stockline.events.handler;

import in.stockline.domain.model.Models.EventType;
import in.stockline.domain.model.Models.OutboxEvent;
import in.stockline.persistence.repository.Store;
import org.springframework.stereotype.Component;

@Component
public class InventoryAdjustedHandler implements OutboxEventHandler {
    private final Store store;
    public InventoryAdjustedHandler(Store store) { this.store = store; }
    @Override public EventType eventType() { return EventType.INVENTORY_ADJUSTED; }
    @Override public void handle(OutboxEvent event) {
        EventPayloadReader.read(event);
        if (event.correlationId() == null || !store.hasOperation(event.correlationId()))
            throw new PermanentEventException("Adjustment event has no inventory operation.");
    }
}
