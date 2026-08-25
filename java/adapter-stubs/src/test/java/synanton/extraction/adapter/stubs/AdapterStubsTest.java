package synanton.extraction.adapter.stubs;

import org.junit.jupiter.api.Test;
import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ExtractionRequest;
import synanton.extraction.spi.model.ObjectRef;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the adapter stubs. No Spring context is loaded.
 */
class AdapterStubsTest {

    // ---- shared fixtures -----------------------------------------------------------------------

    private static final AudioAdapterStub AUDIO = new AudioAdapterStub();
    private static final ImageAdapterStub IMAGE = new ImageAdapterStub();
    private static final VideoAdapterStub VIDEO = new VideoAdapterStub();

    private static ExtractionRequest requestFor(String mediaType) {
        return new ExtractionRequest(
                "op-1",
                "tenant1",
                "key1",
                "ref1",
                new ObjectRef("bucket", "key", "", "a".repeat(64), 1L),
                mediaType,
                ExtractionOptions.defaults(),
                "PRIORITY_NORMAL",
                null
        );
    }

    private static InputStream emptyStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    // ---- AudioAdapterStub.supports -------------------------------------------------------------

    @Test
    void shouldAudioAdapterStubSupportAudioMpeg() {
        assertThat(AUDIO.supports("audio/mpeg")).isTrue();
    }

    @Test
    void shouldAudioAdapterStubSupportAudioWav() {
        assertThat(AUDIO.supports("audio/wav")).isTrue();
    }

    @Test
    void shouldAudioAdapterStubNotSupportVideoMp4() {
        assertThat(AUDIO.supports("video/mp4")).isFalse();
    }

    // ---- AudioAdapterStub.extract --------------------------------------------------------------

    @Test
    void shouldAudioAdapterStubReturnUnsupportedResultForAudioMpeg() {
        AdapterResult result = AUDIO.extract(requestFor("audio/mpeg"), emptyStream());
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
    }

    // ---- ImageAdapterStub.supports -------------------------------------------------------------

    @Test
    void shouldImageAdapterStubSupportImageJpeg() {
        assertThat(IMAGE.supports("image/jpeg")).isTrue();
    }

    @Test
    void shouldImageAdapterStubSupportImagePng() {
        assertThat(IMAGE.supports("image/png")).isTrue();
    }

    @Test
    void shouldImageAdapterStubNotSupportAudioMpeg() {
        assertThat(IMAGE.supports("audio/mpeg")).isFalse();
    }

    // ---- VideoAdapterStub.supports -------------------------------------------------------------

    @Test
    void shouldVideoAdapterStubSupportVideoMp4() {
        assertThat(VIDEO.supports("video/mp4")).isTrue();
    }

    @Test
    void shouldVideoAdapterStubSupportVideoWebm() {
        assertThat(VIDEO.supports("video/webm")).isTrue();
    }

    @Test
    void shouldVideoAdapterStubNotSupportImagePng() {
        assertThat(VIDEO.supports("image/png")).isFalse();
    }

    // ---- shared result invariants --------------------------------------------------------------

    @Test
    void shouldAllStubResultsHaveIsSuccessFalse() {
        AdapterResult audioResult = AUDIO.extract(requestFor("audio/mpeg"), emptyStream());
        AdapterResult imageResult = IMAGE.extract(requestFor("image/jpeg"), emptyStream());
        AdapterResult videoResult = VIDEO.extract(requestFor("video/mp4"), emptyStream());

        assertThat(audioResult.isSuccess()).isFalse();
        assertThat(imageResult.isSuccess()).isFalse();
        assertThat(videoResult.isSuccess()).isFalse();
    }

    @Test
    void shouldAllStubResultsHaveFailureWithErrorCodeErrorUnsupportedMediaType() {
        AdapterResult audioResult = AUDIO.extract(requestFor("audio/mpeg"), emptyStream());
        AdapterResult imageResult = IMAGE.extract(requestFor("image/jpeg"), emptyStream());
        AdapterResult videoResult = VIDEO.extract(requestFor("video/mp4"), emptyStream());

        assertThat(audioResult.failure().errorCode()).isEqualTo("ERROR_UNSUPPORTED_MEDIA_TYPE");
        assertThat(imageResult.failure().errorCode()).isEqualTo("ERROR_UNSUPPORTED_MEDIA_TYPE");
        assertThat(videoResult.failure().errorCode()).isEqualTo("ERROR_UNSUPPORTED_MEDIA_TYPE");
    }

    // ---- processorId ---------------------------------------------------------------------------

    @Test
    void shouldProcessorIdReturnExpectedIdentifiers() {
        assertThat(AUDIO.processorId()).isEqualTo("audio-stub");
        assertThat(IMAGE.processorId()).isEqualTo("image-stub");
        assertThat(VIDEO.processorId()).isEqualTo("video-stub");
    }
}
