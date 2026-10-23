package com.releasesentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.releasesentinel.model.CiEvent;
import com.releasesentinel.testsupport.ReferenceReceiver;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaPipelineTest {
    @Container static final GenericContainer<?> KAFKA = new GenericContainer<>(DockerImageName.parse("apache/kafka:4.2.1"))
            .withExposedPorts(9092)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindPort(19092), new com.github.dockerjava.api.model.ExposedPort(9092))))
            .withEnv("KAFKA_NODE_ID", "1")
            .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
            .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
            .withEnv("KAFKA_LISTENERS", "CONTROLLER://:9093,PLAINTEXT://:9092")
            .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:19092")
            .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
            .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .waitingFor(Wait.forListeningPort());
    static ReferenceReceiver receiver;
    @Autowired KafkaTemplate<String, String> producer;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        try { receiver = new ReferenceReceiver("secret", 0); } catch (Exception e) { throw new IllegalStateException(e); }
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:19092");
        registry.add("SENTINEL_WEBHOOK_URL_ACME", receiver::url);
        registry.add("SENTINEL_WEBHOOK_SECRET_ACME", () -> "secret");
    }

    @AfterAll static void closeReceiver() { if (receiver != null) receiver.close(); }

    @Test @Order(1) void happyPathEventToSignedWebhook() throws Exception {
        int before = receiver.acceptedDeliveries(); publish("happy", "valid");
        assertTrue(awaitWebhook(before + 1));
        assertNotNull(receiver.lastBody());
    }

    @Test @Order(2) void firstFailuresRetryAndDeliverOnceInEffect() throws Exception {
        receiver.failNext(2); int before = receiver.acceptedDeliveries(); publish("retry", "valid");
        assertTrue(awaitWebhook(before + 1));
        assertEquals(1, receiver.acceptedDeliveries() - before);
    }

    @Test @Order(3) void receiverRejectsTamperWrongSecretAndReplay() throws Exception {
        assertEquals(401, postTampered("tamper", "{}x", "{}", "secret", Instant.now().getEpochSecond()));
        assertEquals(401, postSigned("wrong", "{}", "wrong", Instant.now().getEpochSecond()));
        assertEquals(401, postSigned("old", "{}", "secret", Instant.now().minusSeconds(301).getEpochSecond()));
    }

    @Test @Order(6) void permanentFailureReachesDlt() throws Exception {
        receiver.failNext(100); publish("permanent", "valid");
        assertNotNull(await(KafkaTopics.VERDICTS + ".DLT", "permanent"));
        receiver.failNext(0);
    }

    @Test @Order(4) void malformedEventReachesEventsDlq() throws Exception {
        producer.send(KafkaTopics.EVENTS, "bad#1", "not-json").get();
        assertNotNull(await(KafkaTopics.EVENTS_DLQ, "bad#1"));
    }

    @Test @Order(5) void verdictIsReprocessableWithoutLoss() throws Exception {
        publish("duplicate", "valid"); publish("duplicate", "valid");
        assertNotNull(await(KafkaTopics.VERDICTS, "duplicate"));
        assertNotNull(await(KafkaTopics.VERDICTS, "duplicate"));
    }

    private void publish(String id, String ignored) throws Exception {
        var event = new CiEvent(1, id, "acme", "generic", "repo", 1, "sha-" + id, List.of(), "", Instant.now());
        producer.send(KafkaTopics.EVENTS, "repo#1", mapper.writeValueAsString(event)).get();
    }
    private boolean awaitWebhook(int accepted) throws InterruptedException { long end = System.nanoTime() + Duration.ofSeconds(20).toNanos(); while (System.nanoTime() < end) { if (receiver.acceptedDeliveries() >= accepted) return true; Thread.sleep(100); } return false; }
    private int postSigned(String id, String body, String secret, long timestamp) throws Exception { return postTampered(id, body, body, secret, timestamp); }
    private int postTampered(String id, String body, String signedBody, String secret, long timestamp) throws Exception { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); String sig = "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + signedBody).getBytes(StandardCharsets.UTF_8))); var request = HttpRequest.newBuilder(URI.create(receiver.url())).header("X-Sentinel-Delivery-Id", id).header("X-Sentinel-Signature", sig).POST(HttpRequest.BodyPublishers.ofString(body)).build(); return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode(); }
    private String await(String topic, String key) { try (var consumer = consumer()) { consumer.subscribe(List.of(topic)); long end = System.nanoTime() + Duration.ofSeconds(20).toNanos(); while (System.nanoTime() < end) for (var record : consumer.poll(Duration.ofMillis(250))) if (key.equals(record.key())) return record.value(); return null; } }
    private KafkaConsumer<String, String> consumer() { var props = new Properties(); props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:19092"); props.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString()); props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class); props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class); props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); return new KafkaConsumer<>(props); }
}
