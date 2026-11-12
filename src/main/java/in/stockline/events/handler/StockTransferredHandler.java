package in.stockline.events.handler;

import in.stockline.domain.model.Models.EventType;
import in.stockline.domain.model.Models.OutboxEvent;
import in.stockline.persistence.repository.Store;
import org.springframework.stereotype.Component;

@Component
public class StockTransferredHandler implements OutboxEventHandler {
    private final Store store;
    public StockTransferredHandler(Store store) { this.store = store; }
    @Override public EventType eventType() { return EventType.STOCK_TRANSFERRED; }
    @Override public void handle(OutboxEvent event) {
        EventPayloadReader.read(event);
        if (event.correlationId() == null || !store.hasOperation(event.correlationId()))
            throw new PermanentEventException("Transfer event has no inventory operation.");
    }
}
