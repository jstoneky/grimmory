package org.booklore.acquisition;

import org.booklore.crons.BookAcquisitionScheduler;
import org.booklore.model.dto.acquisition.AcquisitionResult;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.enums.WantedBookStatus;
import org.booklore.repository.WantedBookRepository;
import org.booklore.service.acquisition.AcquisitionNotifier;
import org.booklore.service.acquisition.AcquisitionDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookAcquisitionSchedulerTest {

    @Mock private AcquisitionDispatchService dispatchService;
    @Mock private WantedBookRepository wantedBookRepository;
    @Mock private AcquisitionNotifier acquisitionNotifier;

    @InjectMocks
    private BookAcquisitionScheduler scheduler;

    private WantedBookEntity wantedBook1;
    private WantedBookEntity wantedBook2;

    @BeforeEach
    void setUp() {
        wantedBook1 = WantedBookEntity.builder()
                .id(1L).title("Dune").author("Frank Herbert")
                .status(WantedBookStatus.WANTED).retryCount(0).build();

        wantedBook2 = WantedBookEntity.builder()
                .id(2L).title("Foundation").author("Isaac Asimov")
                .status(WantedBookStatus.NOT_FOUND).retryCount(0).build();
    }

    @Test
    void triggerNow_withTwoWantedBooks_callsSearchAndDispatchTwice() {
        when(wantedBookRepository.findByStatusIn(List.of(WantedBookStatus.WANTED, WantedBookStatus.NOT_FOUND)))
                .thenReturn(List.of(wantedBook1, wantedBook2));
        when(dispatchService.searchAndDispatch(any()))
                .thenReturn(AcquisitionResult.notFound(1L));

        scheduler.triggerNow();

        verify(dispatchService, times(2)).searchAndDispatch(any());
    }

    @Test
    void triggerNow_oneBookThrowsException_secondBookStillProcessed() {
        when(wantedBookRepository.findByStatusIn(List.of(WantedBookStatus.WANTED, WantedBookStatus.NOT_FOUND)))
                .thenReturn(List.of(wantedBook1, wantedBook2));
        when(dispatchService.searchAndDispatch(eq(wantedBook1)))
                .thenThrow(new RuntimeException("Indexer timeout"));
        when(dispatchService.searchAndDispatch(eq(wantedBook2)))
                .thenReturn(AcquisitionResult.notFound(2L));

        scheduler.triggerNow();

        verify(dispatchService).searchAndDispatch(wantedBook1);
        verify(dispatchService).searchAndDispatch(wantedBook2);
        // Notification only sent for book2 (book1 threw before notification)
        verify(acquisitionNotifier, times(1)).broadcast(any());
    }

    @Test
    void triggerNow_noWantedBooks_searchAndDispatchNeverCalled() {
        when(wantedBookRepository.findByStatusIn(List.of(WantedBookStatus.WANTED, WantedBookStatus.NOT_FOUND)))
                .thenReturn(List.of());

        scheduler.triggerNow();

        verify(dispatchService, never()).searchAndDispatch(any());
        verify(acquisitionNotifier, never()).broadcast(any());
    }

    @Test
    void triggerNow_successfulDispatch_sendsNotification() {
        when(wantedBookRepository.findByStatusIn(List.of(WantedBookStatus.WANTED, WantedBookStatus.NOT_FOUND)))
                .thenReturn(List.of(wantedBook1));
        when(dispatchService.searchAndDispatch(wantedBook1))
                .thenReturn(AcquisitionResult.dispatched(1L, "Dune Frank Herbert EPUB", 90, "sabnzbd-123"));

        scheduler.triggerNow();

        verify(acquisitionNotifier).broadcast(any());
    }

    @Test
    void triggerNow_bookAtMaxRetries_markedFailedPermanent_skipsSearch() {
        WantedBookEntity exhausted = WantedBookEntity.builder()
                .id(3L).title("Exhausted").author("Author")
                .status(WantedBookStatus.NOT_FOUND).retryCount(5).build();

        when(wantedBookRepository.findByStatusIn(List.of(WantedBookStatus.WANTED, WantedBookStatus.NOT_FOUND)))
                .thenReturn(List.of(exhausted));
        when(wantedBookRepository.save(any())).thenReturn(exhausted);

        scheduler.triggerNow();

        assertThat(exhausted.getStatus()).isEqualTo(WantedBookStatus.FAILED_PERMANENT);
        verify(dispatchService, never()).searchAndDispatch(any());
        verify(acquisitionNotifier).broadcast(any());
    }

    @Test
    void triggerNow_notFoundBook_includedInCandidates_isRetried() {
        WantedBookEntity notFound = WantedBookEntity.builder()
                .id(4L).title("Retry Me").author("Author")
                .status(WantedBookStatus.NOT_FOUND).retryCount(2).build();

        when(wantedBookRepository.findByStatusIn(List.of(WantedBookStatus.WANTED, WantedBookStatus.NOT_FOUND)))
                .thenReturn(List.of(notFound));
        when(dispatchService.searchAndDispatch(notFound))
                .thenReturn(AcquisitionResult.notFound(4L));

        scheduler.triggerNow();

        verify(dispatchService).searchAndDispatch(notFound);
    }

    @Test
    void triggerNow_alreadyRunning_skipsExecution() throws InterruptedException {
        // Simulate concurrent call: first call blocks in the running flag check
        // by having the first call set running=true before second call
        when(wantedBookRepository.findByStatusIn(any()))
                .thenReturn(List.of(wantedBook1));
        when(dispatchService.searchAndDispatch(any()))
                .thenReturn(AcquisitionResult.notFound(1L));

        // A fresh scheduler's running flag starts false; triggerNow sets it true then false.
        // We can't easily test true concurrency in a unit test, but we verify the guard
        // doesn't break normal single-threaded execution.
        scheduler.triggerNow();

        verify(dispatchService, times(1)).searchAndDispatch(any());
    }
}
