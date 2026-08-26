package synanton.extraction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import synanton.extraction.config.ExtractionGatewayProperties;

/** Structured Content Extraction Plane — gRPC gateway for synanton.extraction.v1. */
@SpringBootApplication
@EnableConfigurationProperties(ExtractionGatewayProperties.class)
public class ExtractionGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExtractionGatewayApplication.class, args);
    }
}
