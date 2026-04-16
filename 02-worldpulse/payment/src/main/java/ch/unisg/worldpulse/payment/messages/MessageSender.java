// Adapted from Lab 4: kafka/java/choreography-alternative/payment/messages/MessageSender.java
package ch.unisg.worldpulse.payment.messages;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends payment result events (PaymentReceivedEvent / PaymentFailedEvent)
 * Same logic as the Signup MessageSender
 */
@Component
public class MessageSender {

  public static final String TOPIC_NAME = "worldpulse";

  @Autowired
  private KafkaTemplate<String, String> kafkaTemplate;

  @Autowired
  private ObjectMapper objectMapper;

  @Bean
  public NewTopic autoCreateTopic() {
    return TopicBuilder.name(TOPIC_NAME).partitions(1).replicas(1).build();
  }

  public void send(Message<?> m) {
    try {
      // serialize to JSON, add type header for consumer filtering, send to Kafka
      String jsonMessage = objectMapper.writeValueAsString(m);
      ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC_NAME, jsonMessage);
      record.headers().add("type", m.getType().getBytes(StandardCharsets.UTF_8));
      kafkaTemplate.send(record);
    } catch (Exception e) {
      throw new RuntimeException("Could not send message: " + e.getMessage(), e);
    }
  }
}
