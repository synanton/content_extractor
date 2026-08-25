package synanton.extraction.adapter.document.text;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} that registers the {@link TextModalityAdapter} as a bean.
 *
 * <p>The gateway's component scan picks up this class under the {@code synanton.extraction}
 * base package and makes the adapter available as a
 * {@link synanton.extraction.spi.port.ModalityAdapter} bean.
 */
@Configuration
public class TextAdapterConfiguration {

    /**
     * Registers the Tika-backed text modality adapter.
     *
     * @return a new {@link TextModalityAdapter} instance
     */
    @Bean
    public TextModalityAdapter textModalityAdapter() {
        return new TextModalityAdapter();
    }
}
