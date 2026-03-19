# Distributed Log Processing Pipeline

A fault-tolerant, high-throughput streaming data pipeline designed to ingest, transform, anonymize, and store real-time event logs. 

This project demonstrates a production-grade backend architecture utilizing a distributed message broker to decouple high-volume data generation from downstream processing, complete with cryptographic PII hashing and a real-time observability stack.

## Architecture & Tech Stack

* **Message Broker:** [Apache Kafka](https://kafka.apache.org/) (Handles high-throughput event ingestion)
* **Application Layer:** Java 17 / Gradle (Multi-threaded Producer and Consumer applications)
* **Database:** [PostgreSQL](https://www.postgresql.org/) (Persistent storage for processed events)
* **Observability:** [Prometheus](https://prometheus.io/) & [Grafana](https://grafana.com/) (Real-time metrics and visualization)
* **Containerization:** Docker & Docker Compose (Infrastructure provisioning)

### How It Works
1. **Producer:** A Java application that generates simulated high-volume traffic logs and publishes them to a Kafka topic.
2. **Kafka:** Buffers the incoming stream, ensuring zero data loss during traffic spikes.
3. **Consumer:** A Java application that continuously polls Kafka, parses the JSON payloads, applies SHA-256 hashing to Personally Identifiable Information (User IDs and IP Addresses), and writes the clean data to PostgreSQL.
4. **Monitoring:** The Consumer exposes a `/metrics` endpoint using Micrometer. Prometheus scrapes this endpoint, and Grafana visualizes the exact messages-per-second throughput in real-time.

---

## Local Setup & Execution

### Prerequisites
* [Docker Desktop](https://www.docker.com/products/docker-desktop/) installed and running.
* [Java 17](https://adoptium.net/) installed.
* [Gradle](https://gradle.org/install/) installed (or use the included `./gradlew` wrapper).

### 1. Start the Infrastructure
Navigate to the `docker` directory and spin up Kafka, PostgreSQL, Prometheus, and Grafana using Docker Compose.

```bash
cd docker
docker-compose up -d
```
Note: This will expose Kafka on port 9092, Postgres on 5432, Prometheus on 9090, and Grafana on 3000.

2. Configure Environment Variables
In the root directory of the project, create a .env file to hold your local configuration:

```
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
POSTGRES_USER=pipeline_admin
POSTGRES_PASSWORD=pipeline_password
POSTGRES_DB=log_pipeline
POSTGRES_PORT=5432
```

3. Initialize the Database
Before running the consumer, we need to create the required table in PostgreSQL
```
docker exec -it <postgres_container_name> psql -U pipeline_admin -d log_pipeline -c
"CREATE TABLE IF NOT EXISTS processed_logs
(id SERIAL PRIMARY KEY, event_id UUID NOT NULL, event_timestamp TIMESTAMP NOT NULL, user_id_hash VARCHAR(64) NOT NULL,
ip_address_masked VARCHAR(20) NOT NULL, endpoint VARCHAR(255) NOT NULL, response_time_ms INT, status INT);"
```
