# Convo Audio Watermarking Backend

Backend microservice for audio watermark configuration and detection, built with **Spring Boot (Java)**.
This service integrates with the existing Convo frontend and focuses specifically on **audio watermark processing**.

---

## Project Status

**Stable Branch:** `main`
Production-ready and tested.

Frontend:
[https://github.com/Capstone-Project-BUET-CSE-21/convo-frontend](https://github.com/Capstone-Project-BUET-CSE-21/convo-frontend)

---

## Overview

This microservice provides:

* Audio **watermark configuration management**
* Audio **watermark detection APIs**
* REST-based communication with frontend
* Persistent storage using JPA

---

## Project Structure

```
audio-watermark/
├── pom.xml
├── src/
│   ├── main/java/com/convo/audio_watermark/
│   │   ├── AudioWatermarkApplication.java
│   │   ├── config/
│   │   │   └── WebConfig.java
│   │   ├── controller/
│   │   │   ├── WatermarkConfigController.java
│   │   │   └── WatermarkDetectionController.java
│   │   ├── dto/
│   │   │   ├── WatermarkDetectionRequest.java
│   │   │   └── WatermarkDetectionResponse.java
│   │   ├── entity/
│   │   │   └── WatermarkConfig.java
│   │   ├── repository/
│   │   │   └── WatermarkConfigRepository.java
│   │   └── service/
│   │       ├── WatermarkConfigService.java
│   │       └── WatermarkDetectionService.java
│   └── resources/
│       └── application.properties
```

---

## Key Components

### AudioWatermarkApplication.java

Entry point for the Spring Boot application.

---

### WebConfig.java

Handles:

* CORS configuration
* General web settings

---

### Controllers

#### WatermarkConfigController

* Manage watermark configurations
* Create / update / retrieve settings

#### WatermarkDetectionController

* Accepts audio input
* Returns watermark detection results

---

### DTOs

* **WatermarkDetectionRequest** → Input payload
* **WatermarkDetectionResponse** → Detection results

---

### Entity

* **WatermarkConfig** → Stores watermark parameters

---

### Repository

* **WatermarkConfigRepository** → JPA-based database access

---

### Services

* **WatermarkConfigService** → Business logic for configs
* **WatermarkDetectionService** → Core watermark detection logic

---

## Prerequisites

* Java 21+
* Maven 3.6+ (or use `mvnw`)

Check:

```
java -version
```

---

## Running Locally

### 1. Clone

```
git clone https://github.com/Capstone-Project-BUET-CSE-21/convo-backend
cd convo-backend/audio-watermark
```

### 2. Run

```
./mvnw spring-boot:run
```

(Windows)

```
mvnw.cmd spring-boot:run
```

---

### 3. Server

```
http://localhost:8080
```

---

## Architecture

Simple layered architecture:

```
Controller → Service → Repository → Database
```

### Flow

1. Frontend sends request
2. Controller handles API
3. Service processes logic
4. Repository interacts with DB
5. Response returned as JSON

---

## Technologies

* Java 21
* Spring Boot
* Spring Web
* Spring Data JPA
* Maven

---

## Troubleshooting

**Server not starting**

```
mvn clean install
```

Check:

* Java version
* Port 8080 availability

---

**API not responding**

* Verify correct endpoint URL
* Check backend logs
* Ensure frontend is pointing to correct service

---
