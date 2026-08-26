package synanton.extraction.domain.service;

import synanton.extraction.spi.port.OperationRepository;
import synanton.extraction.spi.port.OperationRepository.ExtractionOperation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OperationQueryService {

    private final OperationRepository operationRepository;

    public OperationQueryService(OperationRepository operationRepository) {
        this.operationRepository = operationRepository;
    }

    public QueryResult getOperations(String tenantId, List<String> operationIds) {
        List<ExtractionOperation> found = operationRepository.findByIds(tenantId, operationIds);
        Set<String> foundIds = new HashSet<>(found.size());
        found.forEach(operation -> foundIds.add(operation.id()));

        List<String> notFound = new ArrayList<>();
        for (String operationId : operationIds) {
            if (!foundIds.contains(operationId)) {
                notFound.add(operationId);
            }
        }
        return new QueryResult(found, notFound);
    }

    public CompletedPage listCompleted(String tenantId, String cursor, int pageSize) {
        List<ExtractionOperation> operations =
                operationRepository.listCompleted(tenantId, cursor, pageSize);
        String nextCursor = "";
        if (!operations.isEmpty()) {
            String lastOperationId = operations.getLast().id();
            nextCursor = operationRepository.findCompletionSequence(lastOperationId)
                    .map(String::valueOf)
                    .orElse("");
        }
        return new CompletedPage(operations, nextCursor);
    }

    public record QueryResult(List<ExtractionOperation> operations, List<String> notFoundOperationIds) {
    }

    public record CompletedPage(List<ExtractionOperation> operations, String nextCursor) {
    }
}
