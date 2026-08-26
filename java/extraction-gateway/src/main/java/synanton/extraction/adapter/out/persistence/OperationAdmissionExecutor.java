package synanton.extraction.adapter.out.persistence;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import synanton.extraction.domain.model.SubmitExtractionCommand;
import synanton.extraction.domain.service.SubmitExtractionService;
import synanton.extraction.spi.port.OperationRepository.ExtractionOperation;

@Component
public class OperationAdmissionExecutor {

    private final SubmitExtractionService submitExtractionService;
    private final TransactionTemplate transactionTemplate;

    public OperationAdmissionExecutor(
            SubmitExtractionService submitExtractionService,
            TransactionTemplate transactionTemplate) {
        this.submitExtractionService = submitExtractionService;
        this.transactionTemplate = transactionTemplate;
    }

    public ExtractionOperation admit(SubmitExtractionCommand command) {
        return transactionTemplate.execute(status -> {
            try {
                return submitExtractionService.submitTransactional(command);
            } catch (JdbcIdempotencyStore.DuplicateIdempotencyKeyException e) {
                return submitExtractionService.submitTransactional(command);
            }
        });
    }
}
