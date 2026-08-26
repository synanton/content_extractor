package synanton.extraction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "extraction-gateway")
public class ExtractionGatewayProperties {

    private int grpcPort = 9091;
    private int maxInboundMessageSizeBytes = 4_259_840;
    private Limits limits = new Limits();
    private Objectstore objectstore = new Objectstore();
    private Async async = new Async();

    public static class Limits {
        private long maxObjectBytes = 268_435_456;
        private long maxDurationSeconds = 900;
        private long maxPayloadBytes = 67_108_864;
        private long maxSyncObjectBytes = 10_485_760;

        public long getMaxObjectBytes() { return maxObjectBytes; }
        public void setMaxObjectBytes(long maxObjectBytes) { this.maxObjectBytes = maxObjectBytes; }
        public long getMaxDurationSeconds() { return maxDurationSeconds; }
        public void setMaxDurationSeconds(long maxDurationSeconds) {
            this.maxDurationSeconds = maxDurationSeconds;
        }
        public long getMaxPayloadBytes() { return maxPayloadBytes; }
        public void setMaxPayloadBytes(long maxPayloadBytes) { this.maxPayloadBytes = maxPayloadBytes; }
        public long getMaxSyncObjectBytes() { return maxSyncObjectBytes; }
        public void setMaxSyncObjectBytes(long maxSyncObjectBytes) {
            this.maxSyncObjectBytes = maxSyncObjectBytes;
        }
    }

    public static class Objectstore {
        private String endpoint = "http://minio:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String region = "us-east-1";
        private boolean pathStyleAccess = true;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public boolean isPathStyleAccess() { return pathStyleAccess; }
        public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }
    }

    public static class Async {
        private int maxConcurrentOperationsPerTenant = 10;
        private long workerPollIntervalMs = 500;
        private long leaseTimeoutSeconds = 300;

        public int getMaxConcurrentOperationsPerTenant() {
            return maxConcurrentOperationsPerTenant;
        }

        public void setMaxConcurrentOperationsPerTenant(int maxConcurrentOperationsPerTenant) {
            this.maxConcurrentOperationsPerTenant = maxConcurrentOperationsPerTenant;
        }

        public long getWorkerPollIntervalMs() {
            return workerPollIntervalMs;
        }

        public void setWorkerPollIntervalMs(long workerPollIntervalMs) {
            this.workerPollIntervalMs = workerPollIntervalMs;
        }

        public long getLeaseTimeoutSeconds() {
            return leaseTimeoutSeconds;
        }

        public void setLeaseTimeoutSeconds(long leaseTimeoutSeconds) {
            this.leaseTimeoutSeconds = leaseTimeoutSeconds;
        }
    }

    public Async getAsync() {
        return async;
    }

    public void setAsync(Async async) {
        this.async = async;
    }

    public int getGrpcPort() { return grpcPort; }
    public void setGrpcPort(int grpcPort) { this.grpcPort = grpcPort; }
    public int getMaxInboundMessageSizeBytes() { return maxInboundMessageSizeBytes; }
    public void setMaxInboundMessageSizeBytes(int maxInboundMessageSizeBytes) {
        this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes;
    }
    public Limits getLimits() { return limits; }
    public void setLimits(Limits limits) { this.limits = limits; }
    public Objectstore getObjectstore() { return objectstore; }
    public void setObjectstore(Objectstore objectstore) { this.objectstore = objectstore; }
}
