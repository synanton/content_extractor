package synanton.extraction.config;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import synanton.extraction.adapter.in.grpc.ExtractionGrpcAdapter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle {

    private volatile Server server;
    private final ExtractionGatewayProperties properties;
    private final ExtractionGrpcAdapter extractionAdapter;

    public GrpcServerLifecycle(
            ExtractionGatewayProperties properties,
            ExtractionGrpcAdapter extractionAdapter) {
        this.properties = properties;
        this.extractionAdapter = extractionAdapter;
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder
                    .forPort(properties.getGrpcPort())
                    .maxInboundMessageSize(properties.getMaxInboundMessageSizeBytes())
                    .addService(extractionAdapter)
                    .build()
                    .start();
            log.info("Extraction gRPC server started on port {}", server.getPort());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to start gRPC server on port " + properties.getGrpcPort(), e);
        }
    }

    public int getBoundPort() {
        if (server == null) {
            throw new IllegalStateException("gRPC server has not started");
        }
        return server.getPort();
    }

    @Override
    public void stop() {
        if (server != null && !server.isShutdown()) {
            log.info("Shutting down extraction gRPC server");
            server.shutdown();
            try {
                if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
