package in.gov.sarthi.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** sampleMessage is one representative grievance message from an already-computed cluster (see GrievanceService.clusterOpenGrievances). */
public record ClusterLabelRequest(
        @NotBlank @Size(max = 2000) String sampleMessage,
        int clusterSize
) {}
