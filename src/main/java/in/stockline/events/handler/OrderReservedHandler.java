package in.stockline.events.handler;

import in.stockline.domain.model.Models.EventType;
import in.stockline.domain.model.Models.OutboxEvent;
import in.stockline.service.inventory.InventoryService;
import org.springframework.stereotype.Component;

@Component
public class OrderReservedHandler implements OutboxEventHandler {
    private final InventoryService inventory;
    public OrderReservedHandler(InventoryService inventory) { this.inventory = inventory; }
    @Override public EventType eventType() { return EventType.ORDER_RESERVED; }
    @Override public void handle(OutboxEvent event) { inventory.fulfill(EventPayloadReader.orderId(event)); }
}
