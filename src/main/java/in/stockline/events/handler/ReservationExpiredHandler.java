package in.stockline.events.handler;

import in.stockline.domain.model.Models.EventType;
import in.stockline.domain.model.Models.OutboxEvent;
import in.stockline.service.inventory.InventoryService;
import org.springframework.stereotype.Component;

@Component
public class ReservationExpiredHandler implements OutboxEventHandler {
    private final InventoryService inventory;
    public ReservationExpiredHandler(InventoryService inventory) { this.inventory = inventory; }
    @Override public EventType eventType() { return EventType.RESERVATION_EXPIRED; }
    @Override public void handle(OutboxEvent event) { inventory.expireReservation(EventPayloadReader.orderId(event)); }
}
