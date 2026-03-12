package com.pipeline.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

public class LogProducer {

    private static final Logger log = LoggerFactory.getLogger(LogProducer.class);
    private static final String TOPIC = "raw-logs";
    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Random random = new Random();

    public static void main(String[] args) {
        log.info("Starting Distributed Log Producer...");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        // Settings for high throughput & reliability
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5); // Wait 5ms to batch records
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384); // 16KB batches

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            while (true) {
                String eventJson = generateMockEvent();
                String key = UUID.randomUUID().toString(); // Distributes data across our 3 partitions

                ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, eventJson);
                
                producer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        log.error("Error sending message", exception);
                    }
                });

                // Control the throughput (e.g., emit an event every 50ms)
                Thread.sleep(50); 
            }
        } catch (InterruptedException e) {
            log.error("Producer interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    private static String generateMockEvent() {
        ObjectNode event = mapper.createObjectNode();
        
        // Simulating raw, unmasked data (PII)
        String[] endpoints = {"/api/login", "/api/checkout", "/api/view-item", "/api/logout"};
        String[] ipPrefixes = {"192.168.1.", "10.0.0.", "172.16.0.", "98.210.45."};
        
        event.put("eventId", UUID.randomUUID().toString());
        event.put("timestamp", Instant.now().toString());
        event.put("userId", "USER_" + (random.nextInt(1000) + 1));
        event.put("ipAddress", ipPrefixes[random.nextInt(ipPrefixes.length)] + random.nextInt(255));
        event.put("endpoint", endpoints[random.nextInt(endpoints.length)]);
        event.put("responseTimeMs", random.nextInt(500) + 10);
        event.put("status", random.nextDouble() > 0.05 ? 200 : 500); // 5% error rate

        return event.toString();
    }
}