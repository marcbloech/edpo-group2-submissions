package ch.unisg.worldpulse.process;

import io.camunda.zeebe.spring.client.annotation.Deployment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Deployment(resources = {
    "classpath:signup-process.bpmn",
    "classpath:payment-process.bpmn",
    "classpath:retryPaymentForm.form"
})
public class ProcessApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessApplication.class, args);
    }
}
