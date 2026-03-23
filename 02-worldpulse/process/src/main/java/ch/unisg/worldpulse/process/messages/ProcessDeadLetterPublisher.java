package ch.unisg.worldpulse.process.messages;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ProcessDeadLetterPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessDeadLetterPublisher.class);
  private static final String DEAD_LETTER_TOPIC = "worldpulse-dead-letter";

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  public ProcessDeadLetterPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
  }

  public void publishProcessStartFailure(String originalMessageJson, String messageType, String traceid, String reason) {
    try {
      String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;
      String payload = objectMapper.writeValueAsString(Map.of(
          "type", "ProcessStartFailedEvent",
          "traceid", traceid,
          "originalType", messageType,
          "reason", safeReason,
          "time", Instant.now().toString(),
          "originalMessage", originalMessageJson));

      kafkaTemplate.send(DEAD_LETTER_TOPIC, payload).get(5, TimeUnit.SECONDS);
      LOG.error("Published process start failure to dead-letter topic '{}' (traceid={})", DEAD_LETTER_TOPIC, traceid);
    } catch (Exception ex) {
      LOG.error("Failed to publish dead-letter event (traceid={})", traceid, ex);
    }
  }
}
