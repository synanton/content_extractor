// extraction-contract: slim library owning the synanton.extraction.v1 protobuf contract.
//
// Both extraction-gateway (server, this repo) and the platform's extraction-client
// depend on this module for generated stubs.
//
// This module MUST NOT depend on content_extractor internals or on platform
// internals. It carries the contract and nothing else — that is what lets the
// extraction implementation change freely behind it (§67.1, §67.2).

plugins {
    java
    alias(libs.plugins.protobuf)
}

dependencies {
    implementation(libs.protobuf.java)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    compileOnly(libs.javax.annotation)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.grpc.inprocess)
}

protobuf {
    protoc {
        artifact = libs.protoc.asProvider().get().toString()
    }
    plugins {
        create("grpc") {
            artifact = libs.protoc.gen.grpc.java.get().toString()
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc") { }
            }
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// The mirror is part of correctness, not a separate chore: a contract that differs
// between repositories is not one contract.
tasks.named("check") {
    dependsOn(rootProject.tasks.named("verifyContractMirror"))
}
