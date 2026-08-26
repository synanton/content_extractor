package synanton.extraction.domain.service;

import synanton.extraction.spi.model.OperationState;
import synanton.extraction.spi.port.OperationRepository;
import synanton.extraction.spi.port.OperationRepository.ExtractionOperation;

import java.util.Optional;

public class CancelOperationService {

    private final OperationRepository operationRepository;

    public CancelOperationService(OperationRepository operationRepository) {
        this.operationRepository = operationRepository;
    }

    public Optional<ExtractionOperation> cancel(String tenantId, String operationId) {
        Optional<ExtractionOperation> existing = operationRepository.findById(operationId);
        if (existing.isEmpty() || !existing.get().tenantId().equals(tenantId)) {
            return Optional.empty();
        }

        ExtractionOperation operation = existing.get();
        if (operation.state().isTerminal()) {
            return existing;
        }

        OperationState current = operation.state();
        if (current.canTransitionTo(OperationState.CANCELLED)) {
            operationRepository.transitionState(operationId, current, OperationState.CANCELLED);
            operationRepository.assignCompletionSequence(operationId);
        }
        return operationRepository.findById(operationId);
    }
}
