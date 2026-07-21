package org.booklore.service.acquisition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Broadcasts acquisition updates to all connected clients.
 *
 * Acquisition events originate on scheduler threads where no authenticated
 * user exists, so upstream's user-scoped NotificationService cannot deliver
 * them; this sends to the shared broadcast destination the UI subscribes to.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcquisitionNotifier {

    public static final String DESTINATION = "/topic/acquisition";

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcast(Object message) {
        try {
            messagingTemplate.convertAndSend(DESTINATION, message);
        } catch (Exception e) {
            log.error("Error broadcasting acquisition update: {}", e.getMessage(), e);
        }
    }
}
