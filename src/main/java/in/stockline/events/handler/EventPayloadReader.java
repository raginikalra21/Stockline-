package in.stockline.events.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.stockline.domain.model.Models.OutboxEvent;

final class EventPayloadReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private EventPayloadReader() {}

    static JsonNode read(OutboxEvent event) {
        try {
            JsonNode payload = JSON.readTree(event.payload());
            if (payload.path("schemaVersion").asInt(-1) != 1)
                throw new PermanentEventException("Unsupported event payload version.");
            return payload;
        } catch (PermanentEventException failure) {
            throw failure;
        } catch (Exception malformed) {
            throw new PermanentEventException("Event payload is invalid.");
        }
    }

    static String requiredText(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new PermanentEventException("Event payload is missing " + name + ".");
        return value.asText();
    }

    static java.util.UUID orderId(OutboxEvent event) {
        if (!"ORDER".equals(event.aggregateType())) throw new PermanentEventException("Order event has an invalid aggregate type.");
        try { return java.util.UUID.fromString(event.aggregateId()); }
        catch (IllegalArgumentException invalid) { throw new PermanentEventException("Order event has an invalid aggregate ID."); }
    }
}
