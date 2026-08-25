package synanton.extraction.adapter.stubs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} that registers all capability-declining adapter stubs as beans.
 *
 * <p>The gateway's component scan picks up this configuration class under the
 * {@code synanton.extraction} base package and makes all three stubs available as
 * {@link synanton.extraction.spi.port.ModalityAdapter} beans.
 */
@Configuration
public class StubsConfiguration {

    /** Registers the audio modality stub. */
    @Bean
    public AudioAdapterStub audioAdapterStub() {
        return new AudioAdapterStub();
    }

    /** Registers the image modality stub. */
    @Bean
    public ImageAdapterStub imageAdapterStub() {
        return new ImageAdapterStub();
    }

    /** Registers the video modality stub. */
    @Bean
    public VideoAdapterStub videoAdapterStub() {
        return new VideoAdapterStub();
    }
}
