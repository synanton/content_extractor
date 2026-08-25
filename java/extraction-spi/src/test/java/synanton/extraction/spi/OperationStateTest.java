package synanton.extraction.spi;

import org.junit.jupiter.api.Test;
import synanton.extraction.spi.model.OperationState;

import static org.assertj.core.api.Assertions.assertThat;

class OperationStateTest {

    // -------------------------------------------------------------------------
    // ACCEPTED transitions
    // -------------------------------------------------------------------------

    @Test
    void shouldAllowAcceptedToQueuedTransition() {
        assertThat(OperationState.ACCEPTED.canTransitionTo(OperationState.QUEUED)).isTrue();
    }

    @Test
    void shouldAllowAcceptedToRunningTransition() {
        assertThat(OperationState.ACCEPTED.canTransitionTo(OperationState.RUNNING)).isTrue();
    }

    @Test
    void shouldAllowAcceptedToCancelledTransition() {
        assertThat(OperationState.ACCEPTED.canTransitionTo(OperationState.CANCELLED)).isTrue();
    }

    @Test
    void shouldAllowAcceptedToExpiredTransition() {
        assertThat(OperationState.ACCEPTED.canTransitionTo(OperationState.EXPIRED)).isTrue();
    }

    // -------------------------------------------------------------------------
    // QUEUED transitions
    // -------------------------------------------------------------------------

    @Test
    void shouldAllowQueuedToRunningTransition() {
        assertThat(OperationState.QUEUED.canTransitionTo(OperationState.RUNNING)).isTrue();
    }

    @Test
    void shouldAllowQueuedToCancelledTransition() {
        assertThat(OperationState.QUEUED.canTransitionTo(OperationState.CANCELLED)).isTrue();
    }

    @Test
    void shouldAllowQueuedToExpiredTransition() {
        assertThat(OperationState.QUEUED.canTransitionTo(OperationState.EXPIRED)).isTrue();
    }

    // -------------------------------------------------------------------------
    // RUNNING transitions
    // -------------------------------------------------------------------------

    @Test
    void shouldAllowRunningToCompletedTransition() {
        assertThat(OperationState.RUNNING.canTransitionTo(OperationState.COMPLETED)).isTrue();
    }

    @Test
    void shouldAllowRunningToPartialTransition() {
        assertThat(OperationState.RUNNING.canTransitionTo(OperationState.PARTIAL)).isTrue();
    }

    @Test
    void shouldAllowRunningToFailedTransition() {
        assertThat(OperationState.RUNNING.canTransitionTo(OperationState.FAILED)).isTrue();
    }

    @Test
    void shouldAllowRunningToCancelledTransition() {
        assertThat(OperationState.RUNNING.canTransitionTo(OperationState.CANCELLED)).isTrue();
    }

    @Test
    void shouldAllowRunningToExpiredTransition() {
        assertThat(OperationState.RUNNING.canTransitionTo(OperationState.EXPIRED)).isTrue();
    }

    // -------------------------------------------------------------------------
    // Illegal forward skips
    // -------------------------------------------------------------------------

    @Test
    void shouldRejectAcceptedToCompletedSkippingStates() {
        assertThat(OperationState.ACCEPTED.canTransitionTo(OperationState.COMPLETED)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Terminal re-entry
    // -------------------------------------------------------------------------

    @Test
    void shouldRejectCompletedToRunningTerminalReentry() {
        assertThat(OperationState.COMPLETED.canTransitionTo(OperationState.RUNNING)).isFalse();
    }

    @Test
    void shouldRejectFailedToRunningTerminalReentry() {
        assertThat(OperationState.FAILED.canTransitionTo(OperationState.RUNNING)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Terminal state identification
    // -------------------------------------------------------------------------

    @Test
    void shouldIdentifyCompletedPartialFailedCancelledExpiredAsTerminal() {
        assertThat(OperationState.COMPLETED.isTerminal()).isTrue();
        assertThat(OperationState.PARTIAL.isTerminal()).isTrue();
        assertThat(OperationState.FAILED.isTerminal()).isTrue();
        assertThat(OperationState.CANCELLED.isTerminal()).isTrue();
        assertThat(OperationState.EXPIRED.isTerminal()).isTrue();
    }

    @Test
    void shouldIdentifyAcceptedQueuedRunningAsNonTerminal() {
        assertThat(OperationState.ACCEPTED.isTerminal()).isFalse();
        assertThat(OperationState.QUEUED.isTerminal()).isFalse();
        assertThat(OperationState.RUNNING.isTerminal()).isFalse();
    }
}
