package synanton.extraction.adapter.out.objectstore;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.spi.model.ObjectRef;
import synanton.extraction.spi.port.SourceObjectReader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

@Component
public class MinioSourceObjectReader implements SourceObjectReader {

    private final S3Client s3;

    public MinioSourceObjectReader(ExtractionGatewayProperties properties) {
        var store = properties.getObjectstore();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(store.getEndpoint()))
                .region(Region.of(store.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(store.getAccessKey(), store.getSecretKey())))
                .forcePathStyle(store.isPathStyleAccess())
                .build();
    }

    MinioSourceObjectReader(S3Client s3) {
        this.s3 = s3;
    }

    @Override
    public InputStream read(ObjectRef ref) throws IOException {
        try {
            return s3.getObject(GetObjectRequest.builder()
                    .bucket(ref.bucket())
                    .key(ref.key())
                    .build());
        } catch (NoSuchKeyException e) {
            throw new IOException("Object not found: " + ref.bucket() + "/" + ref.key(), e);
        } catch (S3Exception e) {
            throw new IOException("Object store read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public long contentLength(ObjectRef ref) throws IOException {
        try {
            return s3.headObject(HeadObjectRequest.builder()
                    .bucket(ref.bucket())
                    .key(ref.key())
                    .build())
                    .contentLength();
        } catch (NoSuchKeyException e) {
            throw new IOException("Object not found: " + ref.bucket() + "/" + ref.key(), e);
        } catch (S3Exception e) {
            throw new IOException("Object store head failed: " + e.getMessage(), e);
        }
    }
}
