// Adapted from Lab 4: kafka/java/checkout/rest/ShopRestController.java

package ch.unisg.worldpulse.signup.rest;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ch.unisg.worldpulse.signup.domain.DeactivationRequest;
import ch.unisg.worldpulse.signup.domain.SignupRequest;
import ch.unisg.worldpulse.signup.domain.UpgradeRequest;
import ch.unisg.worldpulse.signup.messages.Message;
import ch.unisg.worldpulse.signup.messages.MessageSender;

/**
 * REST API entry point for WorldPulse.
 *
 * This is the sole service that accepts HTTP requests from outside.
 * Everything else happens asynchronously through Kafka events.
 *
 * Flow:
 *  1. User sends POST
 *  2. This controller publishes a SignupRequestedEvent to Kafka
 *  3. Payment and Notification services pick it up and act accordingly
 *
 * How to test it:
 *   curl -X POST http://localhost:8091/api/signup \
 *     -H "Content-Type: application/json" \
 *     -d '{"name":"Marc","email":"marc@gmail.com","tier":"PRO"}'
 */
@RestController
public class SignupRestController {

  private static final Logger LOG = LoggerFactory.getLogger(SignupRestController.class);

  @Autowired
  private MessageSender messageSender;

  @PostMapping("/api/signup")
  public String signup(@RequestBody SignupRequest request) {
    ensureUserId(request);
    Message<SignupRequest> message = publish("SignupRequestedEvent", request);
    LOG.info("Signup received for: {}", request);
    return "{\"traceId\": \"" + message.getTraceid() + "\"}";
  }

  @PostMapping("/api/upgrade")
  public String upgrade(@RequestBody UpgradeRequest request) {
    ensureUserId(request);
    Message<UpgradeRequest> message = publish("UpgradeRequestedEvent", request);
    LOG.info("Upgrade requested for: {}", request);
    return "{\"traceId\": \"" + message.getTraceid() + "\"}";
  }

  @PostMapping("/api/deactivate")
  public String deactivate(@RequestBody DeactivationRequest request) {
    ensureUserId(request);
    Message<DeactivationRequest> message = publish("AccountDeactivationRequestedEvent", request);
    LOG.info("Deactivation requested for: {}", request);
    return "{\"traceId\": \"" + message.getTraceid() + "\"}";
  }

  private <T> Message<T> publish(String eventType, T payload) {
    Message<T> message = new Message<>(eventType, payload);
    messageSender.send(message);
    return message;
  }

  private void ensureUserId(SignupRequest request) {
    if (request.getUserId() == null || request.getUserId().isBlank()) {
      request.setUserId(UUID.randomUUID().toString());
    }
  }

  private void ensureUserId(UpgradeRequest request) {
    if (request.getUserId() == null || request.getUserId().isBlank()) {
      request.setUserId(UUID.randomUUID().toString());
    }
  }

  private void ensureUserId(DeactivationRequest request) {
    if (request.getUserId() == null || request.getUserId().isBlank()) {
      request.setUserId(UUID.randomUUID().toString());
    }
  }
}
