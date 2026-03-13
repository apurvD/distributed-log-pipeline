package com.pipeline.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.cdimascio.dotenv.Dotenv;
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.InetSocketAddress;
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
    private static final ObjectMapper mapper = new ObjectMapper();
    private static HikariDataSource dataSource;
    
    // Load .env from the root directory
    private static final Dotenv dotenv = Dotenv.configure().directory("../../").ignoreIfMissing().load();
    
    // Metrics Registry
    private static final PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private static final Counter messagesProcessed = prometheusRegistry.counter("consumer.messages.processed.total");

    public static void main(String[] args) {
        log.info("Starting Distributed Log Consumer...");
        
        startMetricsServer();
        initDatabaseConnection();

        Properties props = new Properties();
        // Read Kafka host from .env, default to localhost if not found
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, dotenv.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "log-processing-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList("raw-logs"));

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, String> record : records) {
                    processRecord(record.value());
                    messagesProcessed.increment(); // Increment our Prometheus counter!
                }
                
                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } catch (Exception e) {
            log.error("Consumer error", e);
        } finally {
            if (dataSource != null) dataSource.close();
        }
    }

    private static void startMetricsServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/metrics", httpExchange -> {
                String response = prometheusRegistry.scrape();
                httpExchange.sendResponseHeaders(200, response.getBytes().length);
                try (OutputStream os = httpExchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });
            new Thread(server::start).start();
            log.info("Metrics server started on http://localhost:8080/metrics");
        } catch (Exception e) {
            log.error("Failed to start metrics server", e);
        }
    }

    private static void initDatabaseConnection() {
        HikariConfig config = new HikariConfig();
        // Load database credentials from .env
        config.setJdbcUrl("jdbc:postgresql://localhost:" + dotenv.get("POSTGRES_PORT", "5433") + "/" + dotenv.get("POSTGRES_DB", "log_pipeline"));
        config.setUsername(dotenv.get("POSTGRES_USER", "pipeline_admin"));
        config.setPassword(dotenv.get("POSTGRES_PASSWORD", "pipeline_password"));
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);
    }

    // ... (processRecord, insertIntoDatabase, hashUserId, and maskIpAddress remain exactly the same as before) ...
    private static void processRecord(String jsonPayload) {
        try {
            JsonNode event = mapper.readTree(jsonPayload);
            String eventId = event.get("eventId").asText();
            Timestamp timestamp = Timestamp.from(Instant.parse(event.get("timestamp").asText()));
            String endpoint = event.get("endpoint").asText();
            int responseTimeMs = event.get("responseTimeMs").asInt();
            int status = event.get("status").asInt();
            String hashedUserId = hashUserId(event.get("userId").asText());
            String maskedIp = maskIpAddress(event.get("ipAddress").asText());

            insertIntoDatabase(eventId, timestamp, hashedUserId, maskedIp, endpoint, responseTimeMs, status);
        } catch (Exception e) {
            log.error("Failed to process record", e);
        }
    }

    private static void insertIntoDatabase(String eventId, Timestamp timestamp, String hashedUserId, 
                                           String maskedIp, String endpoint, int responseTimeMs, int status) {
        String sql = "INSERT INTO processed_logs (event_id, event_timestamp, user_id_hash, ip_address_masked, endpoint, response_time_ms, status) VALUES (?::uuid, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, eventId); pstmt.setTimestamp(2, timestamp); pstmt.setString(3, hashedUserId);
            pstmt.setString(4, maskedIp); pstmt.setString(5, endpoint); pstmt.setInt(6, responseTimeMs); pstmt.setInt(7, status);
            pstmt.executeUpdate();
        } catch (Exception e) {
            log.error("Database insertion failed", e);
        }
    }

    private static String hashUserId(String userId) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(userId.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private static String maskIpAddress(String ipAddress) {
        int lastDotIndex = ipAddress.lastIndexOf('.');
        if (lastDotIndex != -1) return ipAddress.substring(0, lastDotIndex) + ".***";
        return ipAddress;
    }
}