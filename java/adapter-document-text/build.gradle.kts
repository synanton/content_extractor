// adapter-document-text: text/plain, text/html, text/csv, application/epub+zip adapter.
// Backed by Apache Tika; hidden behind the ModalityAdapter SPI.

plugins {
    java
}

dependencies {
    implementation(project(":java:extraction-spi"))

    implementation(libs.tika.core)
    implementation(libs.tika.parsers)
    implementation(libs.slf4j.api)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Spring annotations (@Component, @Value) used at compile time; runtime provided by gateway
    compileOnly(libs.spring.boot.starter)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testAnnotationProcessor(libs.lombok)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
