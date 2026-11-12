package in.stockline.events.handler;

import com.fasterxml.jackson.databind.JsonNode;
import in.stockline.domain.model.Models.EventType;
import in.stockline.domain.model.Models.OutboxEvent;
import in.stockline.persistence.repository.Store;
import in.stockline.service.inventory.InventoryService;
import org.springframework.stereotype.Component;

@Component
public class LowStockHandler implements OutboxEventHandler {
    private final Store store;
    public LowStockHandler(Store store) { this.store = store; }
    @Override public EventType eventType() { return EventType.LOW_STOCK; }
    @Override public void handle(OutboxEvent event) {
        JsonNode payload = EventPayloadReader.read(event);
        String sku = EventPayloadReader.requiredText(payload, "sku");
        String warehouse = EventPayloadReader.requiredText(payload, "warehouseCode");
        var stock = store.stock(sku, warehouse);
        store.saveReplenishmentSuggestion(event, sku, warehouse, stock.available(), stock.minimumAvailable(),
            Math.max(0, stock.minimumAvailable() - stock.available()), InventoryService.now());
    }
}
