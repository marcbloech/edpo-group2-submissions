package ch.unisg.worldpulse.notification.messages;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.unisg.worldpulse.notification.application.NotificationService;

/**
 * Always-active Kafka listener for scanner events (MarketAlertEvent, SocialTrendEvent).
 *
 * Scanner events are inherently choreographic — no BPMN process drives them —
 * so they are consumed directly from Kafka rather than through Zeebe job workers.
 * Uses a dedicated consumer group to stay independent of the legacy-choreography listener.
 */
@Component
public class ScannerEventListener {

  private static final String TOPIC_NAME = "worldpulse";

  @Autowired
  private NotificationService notificationService;

  @Autowired
  private ObjectMapper objectMapper;

  @KafkaListener(id = "worldpulse-scanner-notifications", topics = TOPIC_NAME)
  public void handleEvent(String messageJson, @Header("type") String messageType) throws Exception {
    JsonNode message = objectMapper.readTree(messageJson);
    JsonNode data = message.get("data");
    String traceid = message.get("traceid").asText();

    switch (messageType) {
      case "MarketAlertEvent":
        notificationService.notifyMarketAlert(traceid, data);
        break;
      case "SocialTrendEvent":
        notificationService.notifySocialTrend(traceid, data);
        break;
      default:
        break;
    }
  }
}
