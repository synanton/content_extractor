// extraction-spi: domain model and port interfaces for the Structured Content Extraction Plane.
//
// Pure Java library — no Spring, no protobuf, no JDBC. Adapters and the gateway depend on
// this module, never on each other: extraction-spi is the sole shared vocabulary.

plugins {
    java
}

dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testAnnotationProcessor(libs.lombok)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
