package synanton.extraction.adapter.in.grpc;

import synanton.extraction.domain.model.ExtractionItemCommand;
import synanton.extraction.domain.model.SubmitExtractionCommand;
import synanton.extraction.v1.ExtractionRequestItem;
import synanton.extraction.v1.PriorityClass;
import synanton.extraction.v1.SubmitExtractionBatchRequest;
import synanton.extraction.v1.SubmitExtractionRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class RequestMapper {

    private RequestMapper() {
    }

    static SubmitExtractionCommand toCommand(SubmitExtractionRequest request) {
        return new SubmitExtractionCommand(
                request.getTenantId(),
                request.getIdempotencyKey(),
                List.of(toItemCommand(request.getItem())),
                toPriority(request.getPriorityClass()),
                ResultMapper.expiresAt(request));
    }

    static SubmitExtractionCommand toCommand(SubmitExtractionBatchRequest request) {
        List<ExtractionItemCommand> items = new ArrayList<>();
        for (ExtractionRequestItem item : request.getItemsList()) {
            items.add(toItemCommand(item));
        }
        Instant expiresAt = request.hasExpiresAt()
                ? Instant.ofEpochSecond(request.getExpiresAt().getSeconds(), request.getExpiresAt().getNanos())
                : null;
        return new SubmitExtractionCommand(
                request.getTenantId(),
                request.getIdempotencyKey(),
                items,
                toPriority(request.getPriorityClass()),
                expiresAt);
    }

    private static ExtractionItemCommand toItemCommand(ExtractionRequestItem item) {
        return new ExtractionItemCommand(
                item.getContentRefId(),
                ResultMapper.toObjectRef(item.getSource()),
                item.getMediaType(),
                ResultMapper.toOptions(item.getOptions()));
    }

    private static String toPriority(PriorityClass priorityClass) {
        return priorityClass.name();
    }
}
