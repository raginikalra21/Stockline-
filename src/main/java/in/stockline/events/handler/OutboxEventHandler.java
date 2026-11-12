package in.stockline.events.handler;

import in.stockline.domain.model.Models.EventType;
import in.stockline.domain.model.Models.OutboxEvent;

public interface OutboxEventHandler {
    EventType eventType();
    void handle(OutboxEvent event);
}
