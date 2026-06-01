package ch.unisg.worldpulse.streams.serialization.json;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;

/**
 * Factory class for JSON-based Serdes.
 * Provides typed Serdes for reading raw CloudEvents and other JSON payloads.
 */
public class JsonSerdes {

    /**
     * Serde for raw JSON (CloudEvents from the worldpulse topic).
     */
    public static Serde<JsonNode> JsonNode() {
        JsonSerializer<JsonNode> serializer = new JsonSerializer<>();
        JsonDeserializer<JsonNode> deserializer = new JsonDeserializer<>(JsonNode.class);
        return Serdes.serdeFrom(serializer, deserializer);
    }

    /**
     * Generic Serde for any class that Jackson can handle.
     */
    public static <T> Serde<T> forType(Class<T> clazz) {
        JsonSerializer<T> serializer = new JsonSerializer<>();
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(clazz);
        return Serdes.serdeFrom(serializer, deserializer);
    }
}
