// Adapted from Lab 4: kafka/java/checkout/messages/MessageSender.java

package ch.unisg.worldpulse.signup.messages;

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
 * Sends messages (events) to Kafka.
 * All services in WorldPulse share one Kafka topic called "worldpulse" (for now, at least...)
 */

// self note: Component tells Spring to create one instance of this class at startup
// so other classes can inject it with @Autowired
@Component
public class MessageSender {

  // All events go to single topic
  public static final String TOPIC_NAME = "worldpulse";

  @Autowired
  private KafkaTemplate<String, String> kafkaTemplate;

  // Jackson ObjectMapper (converts Java objects to JSON strings)
  @Autowired
  private ObjectMapper objectMapper;

  // the Kafka topic is created automatically when the app starts
  @Bean
  public NewTopic autoCreateTopic() {
    return TopicBuilder.name(TOPIC_NAME).partitions(1).replicas(1).build();
  }

  /**
   * Sends a Message to Kafka:
   * 1. Serialize the Message object to a JSON string
   * 2. Create a Kafka ProducerRecord with the JSON as value
   * 3. Add the event type as a Kafka header (so consumers can filter events efficiently without parsing the full JSON body)
   * 4. Send it
   */
  public void send(Message<?> m) {
    try {

      // Convert the Message<T> object to a JSON string
      String jsonMessage = objectMapper.writeValueAsString(m);

      // Create a Kafka record
      ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC_NAME, jsonMessage);

      // Add the event type as a Kafka header (lets consumers check the event type
      // from header without having to deserialize the entire JSON)
      record.headers().add("type", m.getType().getBytes(StandardCharsets.UTF_8));

      kafkaTemplate.send(record);
    } catch (Exception e) {
      throw new RuntimeException("Could not send message: " + e.getMessage(), e);
    }
  }
}
