package ch.unisg.worldpulse.process.config;

import ch.unisg.worldpulse.process.messages.ProcessDeadLetterPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;

/**
 * Spring Kafka error handler for the worldpulse listener.
 *
 * <p>Replaces the previous in-listener {@code Thread.sleep} retry loop. That
 * pattern blocked the Kafka consumer thread during back-off, which is an
 * anti-pattern: long sleeps inside a listener method can stall the consumer
 * past {@code max.poll.interval.ms} and trigger group rebalances.</p>
 *
 * <p>This {@link DefaultErrorHandler} keeps the same observable semantics —
 * up to three delivery attempts with a fixed 500 ms back-off between
 * attempts, then DLQ — but the back-off is managed by Spring Kafka's
 * container infrastructure rather than by sleeping the consumer thread.
 * The listener method itself just throws on failure.</p>
 */
@Configuration
public class KafkaErrorHandlerConfig {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaErrorHandlerConfig.class);

  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      ProcessDeadLetterPublisher deadLetterPublisher,
      ObjectMapper objectMapper) {

    // Three delivery attempts total (initial + 2 retries) with a 500 ms
    // pause between attempts. Equivalent dispositions to the previous
    // in-listener loop, but the back-off is managed by Spring Kafka's
    // container (pausing the partition) instead of by sleeping the
    // consumer thread — so the listener can't stall past
    // {@code max.poll.interval.ms} and trigger a rebalance.
    FixedBackOff backOff = new FixedBackOff(500L, 2L);

    return new DefaultErrorHandler(
        (record, exception) -> {
          String payload = String.valueOf(record.value());
          String messageType = headerString(record.headers().lastHeader("type"));
          String traceid = extractTraceid(objectMapper, payload);
          String reason = exception == null ? "unknown" : exception.getMessage();
          LOG.error(
              "All retry attempts exhausted for {} (traceid={}, partition={}, offset={})",
              messageType, traceid, record.partition(), record.offset(), exception);
          deadLetterPublisher.publishProcessStartFailure(payload, messageType, traceid, reason);
        },
        backOff);
  }

  private static String headerString(Header header) {
    if (header == null || header.value() == null) {
      return "unknown";
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }

  private static String extractTraceid(ObjectMapper objectMapper, String payload) {
    if (payload == null || payload.isBlank()) {
      return "unknown";
    }
    try {
      JsonNode tree = objectMapper.readTree(payload);
      JsonNode tid = tree.get("traceid");
      if (tid == null || tid.isNull()) {
        return "unknown";
      }
      String text = tid.asText();
      return text == null || text.isBlank() ? "unknown" : text;
    } catch (Exception ignored) {
      return "unknown";
    }
  }
}
