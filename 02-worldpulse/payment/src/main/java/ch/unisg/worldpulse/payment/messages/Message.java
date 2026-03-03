// Adapted from Lab 4: kafka/java/checkout/messages/Message.java
// see signup/messages/Message.java for detailed comments

package ch.unisg.worldpulse.payment.messages;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * CloudEvents message envelope for the Payment service
 * Identical to signup's Message.java except source = "WorldPulse-Payment"
 */
public class Message<T> {

  // Cloud Events attributes
  private String type;
  private String id = UUID.randomUUID().toString();
  private String source = "WorldPulse-Payment";
  @JsonFormat(shape = JsonFormat.Shape.STRING)
  private Instant time = Instant.now();
  private T data;
  private String datacontenttype = "application/json";
  private String specversion = "1.0";

  // Extension attributes
  private String traceid = UUID.randomUUID().toString();
  private String correlationid;
  private String group = "worldpulse";

  public Message() {
  }

  public Message(String type, T payload) {
    this.type = type;
    this.data = payload;
  }

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
