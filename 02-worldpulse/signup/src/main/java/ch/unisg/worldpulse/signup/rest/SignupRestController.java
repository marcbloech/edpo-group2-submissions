// Adapted from Lab 4: kafka/java/checkout/rest/ShopRestController.java

package ch.unisg.worldpulse.signup.rest;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ch.unisg.worldpulse.signup.domain.SignupRequest;
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

  @Autowired
  private MessageSender messageSender;

  @PostMapping("/api/signup")
  public String signup(@RequestBody SignupRequest request) {

    // Generate a userId if client didnt provide one
    if (request.getUserId() == null) {
      request.setUserId(UUID.randomUUID().toString());
    }

    // Wrap the signup data in a CloudEvents Message and publish to Kafka.
    Message<SignupRequest> message = new Message<>("SignupRequestedEvent", request);
    messageSender.send(message);

    System.out.println("Signup received for: " + request);

    // Return traceId to caller
    return "{\"traceId\": \"" + message.getTraceid() + "\"}";
  }
}
