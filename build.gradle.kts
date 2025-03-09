plugins {
    id("java")
    id("org.springframework.boot") version "3.1.4"
    id("io.spring.dependency-management") version "1.1.3"
}

group = "org.example"
version = "1.0-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
    maven { url = uri("https://repository.apache.org/content/repositories/releases/") }
}

dependencies {
//    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-configuration-processor")

    // Database
    implementation("mysql:mysql-connector-java:8.0.33")
//    runtimeOnly("org.postgresql:postgresql")

    // Telegram Bot API
    implementation("org.telegram:telegrambots-spring-boot-starter:6.5.0")

    // JSON Processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")

    // Apache Mahout (для рекомендаций)
    implementation("org.apache.commons:commons-math3:3.6.1")

    implementation("org.projectlombok:lombok:1.18.26")
    annotationProcessor("org.projectlombok:lombok:1.18.26")

    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")
}

tasks.test {
    useJUnitPlatform()
}