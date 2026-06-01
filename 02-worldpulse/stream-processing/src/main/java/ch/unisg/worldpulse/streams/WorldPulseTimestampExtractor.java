package ch.unisg.worldpulse.streams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom TimestampExtractor for WorldPulse events.
 *
 * - For Avro SpecificRecord with a "timestamp" field: supports {@link Instant} for Avro
 *   {@code timestamp-millis} logical types and {@link Long} epoch millis as fallback.
 * - For raw JSON bytes (CloudEvents): parses the "time" field (ISO 8601) and converts to epoch millis.
 * - Falls back to wall-clock time if extraction fails.
 */
public class WorldPulseTimestampExtractor implements TimestampExtractor {

    private static final Logger logger = LoggerFactory.getLogger(WorldPulseTimestampExtractor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();

        if (value == null) {
            return System.currentTimeMillis();
        }

        // Case 1: Avro SpecificRecord with a "timestamp" field.
        // The Avro `timestamp-millis` logical type is materialized as java.time.Instant
        // in generated SpecificRecord classes, so check for Instant first.
        if (value instanceof SpecificRecord avroRecord) {
            try {
                org.apache.avro.Schema.Field field = avroRecord.getSchema().getField("timestamp");
                if (field != null) {
                    Object ts = avroRecord.get(field.pos());
                    if (ts instanceof Instant instant) {
                        return instant.toEpochMilli();
                    }
                    if (ts instanceof Long longTs) {
                        return longTs;
                    }
                }
            } catch (Exception e) {
                // Field not found or unsupported type — fall through
            }
        }

        // Case 2: Raw JSON bytes — parse CloudEvents "time" field
        if (value instanceof byte[] bytes) {
            try {
                JsonNode node = objectMapper.readTree(bytes);
                JsonNode timeNode = node.get("time");
                if (timeNode != null && timeNode.isTextual()) {
                    return Instant.parse(timeNode.asText()).toEpochMilli();
                }
                // Also try "timestamp" as a numeric field
                JsonNode tsNode = node.get("timestamp");
                if (tsNode != null && tsNode.isNumber()) {
                    return tsNode.asLong();
                }
            } catch (Exception e) {
                logger.debug("Could not extract timestamp from JSON bytes", e);
            }
        }

        // Case 3: JsonNode (already parsed)
        if (value instanceof JsonNode node) {
            try {
                JsonNode timeNode = node.get("time");
                if (timeNode != null && timeNode.isTextual()) {
                    return Instant.parse(timeNode.asText()).toEpochMilli();
                }
                JsonNode tsNode = node.get("timestamp");
                if (tsNode != null && tsNode.isNumber()) {
                    return tsNode.asLong();
                }
            } catch (Exception e) {
                logger.debug("Could not extract timestamp from JsonNode", e);
            }
        }

        // Fallback: use the Kafka record timestamp (set by the producing topology).
        // Kafka Streams propagates timestamps through operators, so for internal
        // topics like correlated-alerts the record timestamp IS the event time —
        // even when the Avro field is named differently (e.g. correlationTimestamp).
        return record.timestamp() > 0 ? record.timestamp() : System.currentTimeMillis();
    }
}
