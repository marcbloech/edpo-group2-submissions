// Adapted from Lab 4: kafka/java/checkout/messages/Message.java
package ch.unisg.worldpulse.signup.messages;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Generic message envelope
 *
 * Standard wrapper for ALL events sent through Kafka
 * generic type <T> is the payload
 *
 * When such a message is sent to Kafka, Jackson serializes it to JSON like this:
 * {
 *   "type": "SignupRequestedEvent",
 *   "id": "some-uuid",
 *   "source": "WorldPulse-Signup",
 *   "time": "2026-02-28T14:00:00Z",
 *   "traceid": "some-uuid",
 *   "data": { ... the payload ... }
 * }
 *
 * For now, each service has its own copy of this class (no shared library for now)
 */


public class Message<T> {

  // --- CloudEvents standard attributes ---
  private String type;                                      // e.g. "SignupRequestedEvent"
  private String id = UUID.randomUUID().toString();         // unique ID for this specific message
  private String source = "WorldPulse-Signup";              // which service created the event
  @JsonFormat(shape = JsonFormat.Shape.STRING)              // serialize Instant as ISO string, not a number
  private Instant time = Instant.now();                     // when the event was created
  private T data;                                           // the actual payload (e.g. SignupRequest object)
  private String datacontenttype = "application/json";
  private String specversion = "1.0";

  // --- Extension attributes ---
  private String traceid = UUID.randomUUID().toString();    // like a "session ID" for events to track across services
  private String correlationid;                             // optional, can link related events together
  private String group = "worldpulse";                      // identifies which application/system event belongs to

  // Default constructor for Jackson for deserialization (JSON --> Java object)
  public Message() {
  }

  // creating a NEW event (generates a fresh traceid)
  public Message(String type, T payload) {
    this.type = type;
    this.data = payload;
  }

  // RESPONDING to an existing event (preserves original traceid !)
  // (to keep the same trace across signup, payment, notification)
  public Message(String type, String traceid, T payload) {
    this.type = type;
    this.traceid = traceid;
    this.data = payload;
  }

  @Override
  public String toString() {
    return "Message [type=" + type + ", id=" + id + ", time=" + time + ", data=" + data
        + ", correlationid=" + correlationid + ", traceid=" + traceid + "]";
  }

  // --- Getters and setters ---
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public Instant getTime() { return time; }
  public void setTime(Instant time) { this.time = time; }
  public T getData() { return data; }
  public void setData(T data) { this.data = data; }
  public String getTraceid() { return traceid; }
  public void setTraceid(String traceid) { this.traceid = traceid; }
  public String getCorrelationid() { return correlationid; }
  public void setCorrelationid(String correlationid) { this.correlationid = correlationid; }
  public String getSource() { return source; }
  public String getDatacontenttype() { return datacontenttype; }
  public String getSpecversion() { return specversion; }
  public String getGroup() { return group; }
}
