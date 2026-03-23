package ch.unisg.worldpulse.process.messages;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.unisg.worldpulse.process.usecase.StartSignupProcessUseCase;

@Component
public class MessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(MessageListener.class);
    private static final String TOPIC_NAME = "worldpulse";
    private static final String SIGNUP_REQUESTED_EVENT = "SignupRequestedEvent";
    private static final int MAX_PROCESS_START_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    private final ObjectMapper objectMapper;
    private final StartSignupProcessUseCase startSignupProcessUseCase;
    private final ProcessDeadLetterPublisher deadLetterPublisher;

    public MessageListener(
            ObjectMapper objectMapper,
            StartSignupProcessUseCase startSignupProcessUseCase,
            ProcessDeadLetterPublisher deadLetterPublisher) {
        this.objectMapper = objectMapper;
        this.startSignupProcessUseCase = startSignupProcessUseCase;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    @KafkaListener(id = "worldpulse-process", topics = TOPIC_NAME)
    public void handleEvent(String messageJson, @Header("type") String messageType) throws Exception {

        if (!SIGNUP_REQUESTED_EVENT.equals(messageType)) {
            return; // only react to signup events
        }

        JsonNode message = objectMapper.readTree(messageJson);
        JsonNode data = message.get("data");
        String traceid = message.has("traceid") ? message.get("traceid").asText() : "";

        String name = data != null && data.has("name") ? data.get("name").asText() : "unknown";
        String email = data != null && data.has("email") ? data.get("email").asText() : "";
        String tier = data != null && data.has("tier") ? data.get("tier").asText() : "";

        LOG.info("Received SignupRequestedEvent, starting signup-process for '{}' (traceid={})", name, traceid);

        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_PROCESS_START_ATTEMPTS; attempt++) {
            try {
                startSignupProcessUseCase.start(name, email, tier, traceid);
                return;
            } catch (Exception ex) {
                lastException = ex;
                LOG.warn(
                        "Process start attempt {}/{} failed (traceid={}): {}",
                        attempt,
                        MAX_PROCESS_START_ATTEMPTS,
                        traceid,
                        ex.getMessage());
                Thread.sleep(RETRY_DELAY.toMillis());
            }
        }

        LOG.error("Process start failed after {} attempts (traceid={})", MAX_PROCESS_START_ATTEMPTS, traceid, lastException);
        deadLetterPublisher.publishProcessStartFailure(messageJson, messageType, traceid, lastException == null ? "unknown" : lastException.getMessage());
    }
}
