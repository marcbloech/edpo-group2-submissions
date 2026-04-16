// Adapted from Lab 4: kafka/java/choreography-alternative/payment/messages/MessageListener.java
package ch.unisg.worldpulse.payment.messages;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.unisg.worldpulse.payment.application.PaymentService;

/**
 * Listens for events on the "worldpulse" Kafka topic and handles payment-related things
 * --> Choreography pattern: this one reacts to events it cares about and ignores the rest
 * Flow: SignupRequestedEvent arrives → process payment → publish PaymentReceived or PaymentFailed
 */
@Component
@Profile("legacy-choreography")
public class MessageListener {

  @Autowired
  private MessageSender messageSender;

  @Autowired
  private PaymentService paymentService;

  @Autowired
  private ObjectMapper objectMapper;

  /**
   * Triggers whenever new message arrives thanks to @KafkaListener (for id worldpulse-payment)
   * @param messageJson the raw JSON string from Kafka
   * @param messageType the event type from the Kafka header (e.g. "SignupRequestedEvent")
   */
  @Transactional
  @KafkaListener(id = "worldpulse-payment", topics = MessageSender.TOPIC_NAME)
  public void handleEvent(String messageJson, @Header("type") String messageType) throws Exception {

    if ("SignupRequestedEvent".equals(messageType)) { // only react to signup events — ignore everything else

      // Parse the JSON message. JsonNode instead of deserializing (for keeping it simple)
      JsonNode message = objectMapper.readTree(messageJson);
      JsonNode data = message.get("data");          // SignupRequest payload
      String traceid = message.get("traceid").asText(); // preserve trace for end-to-end tracking

      // Extract what is needed for payment processing
      String tier = data.get("tier").asText();
      String userId = data.get("userId").asText();

      // Process the payment (simulated with random success/failure)
      PaymentService.PaymentResult result = paymentService.processPayment(userId, tier);

      // Publish result as a NEW event
      if (result.success()) {
        messageSender.send(new Message<>("PaymentReceivedEvent", traceid, data));
      } else {
        messageSender.send(new Message<>("PaymentFailedEvent", traceid, data));
      }
    }
  }
}
