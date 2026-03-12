package com.pipeline.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Properties;

public class LogConsumer {

    private static final Logger log = LoggerFactory.getLogger(LogConsumer.class);
    private static final String TOPIC = "raw-logs";
    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static HikariDataSource dataSource;

    public static void main(String[] args) {
        log.info("Starting Distributed Log Consumer...");
        initDatabaseConnection();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "log-processing-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); // Start from beginning if no offset exists
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // We will commit manually after processing

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(TOPIC));

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, String> record : records) {
                    processRecord(record.value());
                }
                
                // Commit offsets only after successfully processing the batch
                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } catch (Exception e) {
            log.error("Consumer error", e);
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    private static void initDatabaseConnection() {
        HikariConfig config = new HikariConfig();
        // Since the app runs on the host (outside Docker), it connects to the exposed port 5433
        config.setJdbcUrl("jdbc:postgresql://localhost:5433/log_pipeline");
        config.setUsername("pipeline_admin");
        config.setPassword("pipeline_password");
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);
    }

    private static void processRecord(String jsonPayload) {
        try {
            JsonNode event = mapper.readTree(jsonPayload);

            String eventId = event.get("eventId").asText();
            Timestamp timestamp = Timestamp.from(Instant.parse(event.get("timestamp").asText()));
            String endpoint = event.get("endpoint").asText();
            int responseTimeMs = event.get("responseTimeMs").asInt();
            int status = event.get("status").asInt();

            // PII Anonymization
            String rawUserId = event.get("userId").asText();
            String hashedUserId = hashUserId(rawUserId);

            String rawIp = event.get("ipAddress").asText();
            String maskedIp = maskIpAddress(rawIp);

            insertIntoDatabase(eventId, timestamp, hashedUserId, maskedIp, endpoint, responseTimeMs, status);

        } catch (Exception e) {
            log.error("Failed to process record: " + jsonPayload, e);
        }
    }

    private static void insertIntoDatabase(String eventId, Timestamp timestamp, String hashedUserId, 
                                           String maskedIp, String endpoint, int responseTimeMs, int status) {
        String sql = "INSERT INTO processed_logs (event_id, event_timestamp, user_id_hash, ip_address_masked, endpoint, response_time_ms, status) VALUES (?::uuid, ?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
             
            pstmt.setString(1, eventId);
            pstmt.setTimestamp(2, timestamp);
            pstmt.setString(3, hashedUserId);
            pstmt.setString(4, maskedIp);
            pstmt.setString(5, endpoint);
            pstmt.setInt(6, responseTimeMs);
            pstmt.setInt(7, status);
            
            pstmt.executeUpdate();
        } catch (Exception e) {
            log.error("Database insertion failed for event: " + eventId, e);
        }
    }

    private static String hashUserId(String userId) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(userId.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private static String maskIpAddress(String ipAddress) {
        int lastDotIndex = ipAddress.lastIndexOf('.');
        if (lastDotIndex != -1) {
            return ipAddress.substring(0, lastDotIndex) + ".***";
        }
        return ipAddress;
    }
}