package com.saas.backend.operations;
import org.springframework.stereotype.Component;
import org.slf4j.*;
import java.util.UUID;
/** Console adapter only. Event ID is the deduplication key for a future external provider. */
@Component
public class NotificationSender {
    public void send(UUID event,UUID tenant,UUID recipient,UUID appointment) {
        LoggerFactory.getLogger(getClass()).info("Reminder delivered channel=console eventId={} recipientId={} appointmentId={}",event,recipient,appointment);
    }
}
