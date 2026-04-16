// Adapted from Lab 4: kafka/java/choreography-alternative/payment/messages/MessageListener.java
//
// *** --> This IS THE EVENT NOTIFICATION PATTERN IMPLEMENTATION (E2 goal) ***

package ch.unisg.worldpulse.notification.messages;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.unisg.worldpulse.notification.application.NotificationService;

@Component
@Profile("legacy-choreography")
public class MessageListener {

  private static final String TOPIC_NAME = "worldpulse";

  @Autowired
  private NotificationService notificationService;

  @Autowired
  private ObjectMapper objectMapper;

  /**
   * Handles ALL events on the "worldpulse" topic.
   *
   * the Notification service reacts to ALL event types and
   * sends user-facing notifications accordingly.
   *
   * Hint: consumer group "worldpulse-notification" is different from "worldpulse-payment"
   * so Kafka delivers every event to BOTH services
   */
  @KafkaListener(id = "worldpulse-notification", topics = TOPIC_NAME)
  public void handleEvent(String messageJson, @Header("type") String messageType) throws Exception {

    // Parse the JSON event
    JsonNode message = objectMapper.readTree(messageJson);
    JsonNode data = message.get("data");
    String traceid = message.get("traceid").asText();

    // Route to the appropriate notification method based on event type
    // (different mails depending on event typ)
    switch (messageType) {
      case "SignupRequestedEvent":
        notificationService.notifySignup(traceid, data);
        break;
      case "PaymentReceivedEvent":
        notificationService.notifyPaymentReceived(traceid, data);
        break;
      case "PaymentFailedEvent":
        notificationService.notifyPaymentFailed(traceid, data);
        break;
      case "PaymentRefundedEvent":
        notificationService.notifyPaymentRefunded(traceid, data);
        break;
      case "PaymentRefundFailedEvent":
        notificationService.notifyPaymentRefundFailed(traceid, data);
        break;
      default:
        // ignore other events
        break;
    }
  }
}
