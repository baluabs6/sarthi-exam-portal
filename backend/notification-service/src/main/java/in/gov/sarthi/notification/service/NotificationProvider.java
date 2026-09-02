package in.gov.sarthi.notification.service;

public interface NotificationProvider {
    String getName();
    void send(String rollNumber, String message) throws Exception;
}
