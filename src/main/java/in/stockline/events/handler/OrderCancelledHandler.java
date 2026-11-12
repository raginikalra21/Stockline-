package in.stockline.events.handler;

import in.stockline.domain.model.Models.EventType;
import in.stockline.domain.model.Models.OrderStatus;
import in.stockline.domain.model.Models.OutboxEvent;
import in.stockline.service.inventory.InventoryService;
import org.springframework.stereotype.Component;

@Component
public class OrderCancelledHandler implements OutboxEventHandler {
    private final InventoryService inventory;
    public OrderCancelledHandler(InventoryService inventory) { this.inventory = inventory; }
    @Override public EventType eventType() { return EventType.ORDER_CANCELLED; }
    @Override public void handle(OutboxEvent event) {
        if (inventory.order(EventPayloadReader.orderId(event)).status() != OrderStatus.CANCELLED)
            throw new PermanentEventException("Cancellation event does not match the order state.");
    }
}
