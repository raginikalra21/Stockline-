package in.stockline.events.handler;

import in.stockline.domain.model.Models.EventType;
import in.stockline.domain.model.Models.OrderStatus;
import in.stockline.domain.model.Models.OutboxEvent;
import in.stockline.service.inventory.InventoryService;
import org.springframework.stereotype.Component;

@Component
public class OrderFulfilledHandler implements OutboxEventHandler {
    private final InventoryService inventory;
    public OrderFulfilledHandler(InventoryService inventory) { this.inventory = inventory; }
    @Override public EventType eventType() { return EventType.ORDER_FULFILLED; }
    @Override public void handle(OutboxEvent event) {
        if (inventory.order(EventPayloadReader.orderId(event)).status() != OrderStatus.FULFILLED)
            throw new PermanentEventException("Fulfillment event does not match the order state.");
    }
}
