package org.booklore.acquisition;

import org.booklore.service.acquisition.AcquisitionNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AcquisitionNotifierTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private AcquisitionNotifier notifier;

    @Test
    void broadcast_sendsToSharedTopicWithoutRequiringAuthenticatedUser() {
        var payload = new Object();

        notifier.broadcast(payload);

        // The UI subscribes to the broadcast destination; a user-scoped send
        // (convertAndSendToUser) would never reach it from scheduler threads.
        verify(messagingTemplate).convertAndSend(eq("/topic/acquisition"), eq(payload));
    }

    @Test
    void broadcast_swallowsMessagingFailures() {
        doThrow(new IllegalStateException("broker down"))
                .when(messagingTemplate).convertAndSend(eq("/topic/acquisition"), eq(new Object()));

        assertDoesNotThrow(() -> notifier.broadcast(new Object()));
    }
}
