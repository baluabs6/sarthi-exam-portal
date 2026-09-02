package in.gov.sarthi.result.model;

import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

public record ResultResponse(String rollNumber, String examId, String name, Integer score, String status, String declaredAt) {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneOffset.UTC);

    public static ResultResponse from(Result r) {
        return new ResultResponse(
                r.getRollNumber(), r.getExamId(), r.getName(), r.getScore(), r.getStatus(),
                FORMAT.format(r.getDeclaredAt())
        );
    }
}
