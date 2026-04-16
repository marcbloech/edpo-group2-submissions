package ch.unisg.worldpulse.notification.messages;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes notification outcome events to Kafka.
 *
 * Same pattern as signup and payment MessageSenders — all services
 * share the "worldpulse" topic with event type in a Kafka header.
 */
@Component
public class MessageSender {

  public static final String TOPIC_NAME = "worldpulse";

  @Autowired
  private KafkaTemplate<String, String> kafkaTemplate;

  @Autowired
  private ObjectMapper objectMapper;

  public void send(Message<?> m) {
    try {
      String jsonMessage = objectMapper.writeValueAsString(m);
      ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC_NAME, jsonMessage);
      record.headers().add("type", m.getType().getBytes(StandardCharsets.UTF_8));
      kafkaTemplate.send(record);
    } catch (Exception e) {
      throw new RuntimeException("Could not send message: " + e.getMessage(), e);
    }
  }
}
