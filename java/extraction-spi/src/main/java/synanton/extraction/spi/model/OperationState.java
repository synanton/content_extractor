package synanton.extraction.spi.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states of an extraction operation.
 *
 * <p>All state transitions must pass through {@link #canTransitionTo(OperationState)}.
 */
public enum OperationState {

    ACCEPTED,
    QUEUED,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
    EXPIRED;

    private static final Set<OperationState> TERMINAL_STATES =
            EnumSet.of(COMPLETED, PARTIAL, FAILED, CANCELLED, EXPIRED);

    /**
     * Returns {@code true} if this state may legally transition to {@code next}.
     *
     * <p>Terminal states cannot transition to any other state. The legal non-terminal transitions
     * are:
     * <ul>
     *   <li>{@code ACCEPTED}  → {@code QUEUED}, {@code RUNNING}, {@code CANCELLED}, {@code EXPIRED}</li>
     *   <li>{@code QUEUED}    → {@code RUNNING}, {@code CANCELLED}, {@code EXPIRED}</li>
     *   <li>{@code RUNNING}   → {@code COMPLETED}, {@code PARTIAL}, {@code FAILED},
     *                            {@code CANCELLED}, {@code EXPIRED}</li>
     * </ul>
     *
     * @param next the target state
     * @return {@code true} when the transition is permitted
     */
    public boolean canTransitionTo(OperationState next) {
        if (isTerminal()) {
            return false;
        }
        return switch (this) {
            case ACCEPTED -> EnumSet.of(QUEUED, RUNNING, CANCELLED, EXPIRED).contains(next);
            case QUEUED   -> EnumSet.of(RUNNING, CANCELLED, EXPIRED).contains(next);
            case RUNNING  -> EnumSet.of(COMPLETED, PARTIAL, FAILED, CANCELLED, EXPIRED).contains(next);
            default       -> false;
        };
    }

    /**
     * Returns {@code true} when this state is terminal, meaning no further transitions are
     * permitted: {@code COMPLETED}, {@code PARTIAL}, {@code FAILED}, {@code CANCELLED},
     * or {@code EXPIRED}.
     *
     * @return {@code true} if this is a terminal state
     */
    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }
}
