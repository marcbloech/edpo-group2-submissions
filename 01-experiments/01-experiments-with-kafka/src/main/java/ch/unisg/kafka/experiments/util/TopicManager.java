package ch.unisg.kafka.experiments.util;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;

import java.util.*;
import java.util.concurrent.ExecutionException;

public class TopicManager {

    private final AdminClient admin;

    public TopicManager(String bootstrapServers) {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        this.admin = AdminClient.create(props);
    }

    public TopicManager(AdminClient admin) {
        this.admin = admin;
    }

    public void createTopic(String topicName, int partitions, int replicationFactor) throws Exception {
        Set<String> existing = admin.listTopics().names().get();

        // check if topic already exists, if so, print a message and skip creation
        if (existing.contains(topicName)) {
            System.out.printf("[TopicManager] Topic already exists: %s%n", topicName);
        } else {
            System.out.printf("[TopicManager] Creating topic: %s (partitions=%d, rf=%d)%n",
                    topicName, partitions, replicationFactor);
            NewTopic newTopic = new NewTopic(topicName, partitions, (short) replicationFactor);
            admin.createTopics(Collections.singleton(newTopic)).all().get();
        }
    }

    public void deleteTopic(String topicName) {
        try {
            Set<String> existing = admin.listTopics().names().get();
            if (existing.contains(topicName)) {
                System.out.printf("[TopicManager] Deleting topic: %s%n", topicName);
                admin.deleteTopics(Collections.singleton(topicName)).all().get();
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            System.out.printf("[TopicManager] Could not delete topic %s: %s%n", topicName, e.getMessage());
        }
    }

    public void recreateTopic(String topicName, int partitions, int replicationFactor) throws Exception {
        deleteTopic(topicName);
        createTopic(topicName, partitions, replicationFactor);
    }

    public TopicDescription describeTopic(String topicName) throws ExecutionException, InterruptedException {
        Map<String, TopicDescription> descriptions =
                admin.describeTopics(Collections.singleton(topicName)).all().get();
        return descriptions.get(topicName);
    }

    public AdminClient getAdmin() {
        return admin;
    }

    public void close() {
        admin.close();
    }
}
