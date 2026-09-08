package in.gov.sarthi.assistant.dto;

public record ClusterLabelResponse(boolean aiEnabled, String label) {
    public static ClusterLabelResponse disabled() {
        return new ClusterLabelResponse(false, null);
    }
}
