// extraction-gateway: Spring Boot gRPC server — the sole entry point for the extraction plane.
//
// Owns: admission, operation lifecycle, routing to modality adapters, PostgreSQL state store.
// All adapters are on the classpath and discovered via component scan.

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    // Contract (gRPC stubs + proto messages)
    implementation(project(":java:extraction-contract"))

    // Domain model + port interfaces
    implementation(project(":java:extraction-spi"))

    // Adapters — discovered by Spring component scan at runtime
    implementation(project(":java:adapter-document-text"))
    implementation(project(":java:adapter-document-pdf"))
    implementation(project(":java:adapter-stubs"))

    // Spring Boot
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)

    // PostgreSQL + Flyway
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    // gRPC server transport
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    compileOnly(libs.javax.annotation)

    // Object storage (MinIO via S3 API)
    implementation(libs.aws.s3)

    // JSON (payload digest / mapping helpers)
    implementation(libs.jackson.databind)

    // Micrometer (Prometheus via actuator)
    implementation(libs.micrometer.core)

    // Logging
    implementation(libs.logback.classic)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Test
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.testing)

    testImplementation(libs.archunit.junit5)

    // In-memory H2 for unit tests that touch the store
    testImplementation(libs.h2)

    // TestContainers for full integration tests (SCEP-4)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName.set("extraction-gateway")
    mainClass.set("synanton.extraction.ExtractionGatewayApplication")
}

tasks.named<Jar>("jar") {
    enabled = false
}
