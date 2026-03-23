package ch.unisg.worldpulse.process.usecase;

import java.util.Map;

import io.camunda.zeebe.client.ZeebeClient;
import org.springframework.stereotype.Component;

@Component
public class StartSignupProcessUseCase {

  private final ZeebeClient zeebeClient;

  public StartSignupProcessUseCase(ZeebeClient zeebeClient) {
    this.zeebeClient = zeebeClient;
  }

  public void start(String name, String email, String tier, String traceid) {
    zeebeClient.newCreateInstanceCommand()
        .bpmnProcessId("signup-process")
        .latestVersion()
        .variables(Map.of(
            "name", name,
            "email", email,
            "tier", tier,
            "traceid", traceid
        ))
        .send()
        .join();
  }
}
