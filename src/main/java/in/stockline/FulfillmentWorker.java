package in.stockline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class FulfillmentWorker {
    private static final Logger log = LoggerFactory.getLogger(FulfillmentWorker.class);

    private final Store store;
    private final InventoryService inventory;

    FulfillmentWorker(Store store, InventoryService inventory) {
        this.store = store;
        this.inventory = inventory;
    }

    @Scheduled(fixedDelayString = "${stockline.worker-interval-ms:500}")
    void processDueOrders() {
        for (var orderId : store.dueEvents(InventoryService.now())) {
            try {
                inventory.fulfill(orderId);
            } catch (RuntimeException failure) {
                log.warn("Fulfillment will retry for order {}", orderId, failure);
            }
        }
    }
}
