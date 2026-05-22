package org.booklore.acquisition;

import org.booklore.model.dto.acquisition.AcquisitionResult;
import org.booklore.model.dto.acquisition.NzbResult;
import org.booklore.model.entity.AcquisitionClientEntity;
import org.booklore.model.entity.AcquisitionIndexerEntity;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.enums.AcquisitionClientType;
import org.booklore.model.enums.JobHistoryStatus;
import org.booklore.model.enums.WantedBookStatus;
import org.booklore.repository.AcquisitionClientRepository;
import org.booklore.repository.AcquisitionIndexerRepository;
import org.booklore.repository.AcquisitionJobHistoryRepository;
import org.booklore.repository.WantedBookRepository;
import org.booklore.service.acquisition.AcquisitionDispatchService;
import org.booklore.service.acquisition.ConfidenceScorer;
import org.booklore.service.acquisition.NewznabClient;
import org.booklore.service.acquisition.SabnzbdClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AcquisitionDispatchServiceTest {

    @Mock private AcquisitionIndexerRepository indexerRepository;
    @Mock private AcquisitionClientRepository clientRepository;
    @Mock private WantedBookRepository wantedBookRepository;
    @Mock private AcquisitionJobHistoryRepository historyRepository;
    @Mock private NewznabClient newznabClient;
    @Mock private SabnzbdClient sabnzbdClient;
    @Mock private ConfidenceScorer confidenceScorer;

    @InjectMocks
    private AcquisitionDispatchService service;

    private WantedBookEntity wantedEntity;
    private AcquisitionIndexerEntity indexer;
    private AcquisitionClientEntity sabClient;
    private NzbResult goodResult;

    @BeforeEach
    void setUp() {
        lenient().when(indexerRepository.existsByEnabledTrue()).thenReturn(true);
        lenient().when(clientRepository.existsByEnabledTrue()).thenReturn(true);

        wantedEntity = WantedBookEntity.builder()
                .id(1L)
                .title("Dune")
                .author("Frank Herbert")
                .isbn13("9780441013593")
                .status(WantedBookStatus.WANTED)
                .addedAt(Instant.now())
                .build();

        indexer = AcquisitionIndexerEntity.builder()
                .id(1L)
                .name("TestIndexer")
                .url("https://indexer.example.com")
                .apiKey("key")
                .enabled(true)
                .priority(0)
                .createdAt(Instant.now())
                .build();

        sabClient = AcquisitionClientEntity.builder()
                .id(1L)
                .name("SABnzbd")
                .type(AcquisitionClientType.SABNZBD)
                .url("http://sabnzbd.local")
                .apiKey("sabkey")
                .category("books")
                .enabled(true)
                .createdAt(Instant.now())
                .build();

        goodResult = new NzbResult("Dune Frank Herbert EPUB",
                "https://indexer.com/nzb/dune.nzb",
                5_242_880L, Instant.now(), 42, "TestIndexer");
    }

    @Test
    void searchAndDispatch_confidentMatch_dispatchesToSabnzbd_setsDownloadingStatus() {
        when(wantedBookRepository.save(any())).thenReturn(wantedEntity);
        when(indexerRepository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(indexer));
        when(newznabClient.searchBooks(eq(indexer), any())).thenReturn(List.of(goodResult));
        when(confidenceScorer.calculateConfidence(eq(wantedEntity), eq(goodResult))).thenReturn(85);
        when(confidenceScorer.meetsThreshold(85)).thenReturn(true);
        when(clientRepository.findByEnabledTrue()).thenReturn(List.of(sabClient));
        when(sabnzbdClient.sendNzb(eq(sabClient), anyString(), anyString()))
                .thenReturn("SABnzbd_nzo_abc123");
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AcquisitionResult result = service.searchAndDispatch(wantedEntity);

        assertThat(result.found()).isTrue();
        assertThat(result.sabnzbdJobId()).isEqualTo("SABnzbd_nzo_abc123");
        assertThat(result.confidence()).isEqualTo(85);

        verify(sabnzbdClient).sendNzb(eq(sabClient), anyString(), anyString());
        assertThat(wantedEntity.getStatus()).isEqualTo(WantedBookStatus.DOWNLOADING);
    }

    @Test
    void searchAndDispatch_noResults_setsNotFoundStatus_returnsNotFound() {
        when(wantedBookRepository.save(any())).thenReturn(wantedEntity);
        when(indexerRepository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(indexer));
        when(newznabClient.searchBooks(any(), any())).thenReturn(List.of());

        AcquisitionResult result = service.searchAndDispatch(wantedEntity);

        assertThat(result.found()).isFalse();
        assertThat(wantedEntity.getStatus()).isEqualTo(WantedBookStatus.NOT_FOUND);
        verify(sabnzbdClient, never()).sendNzb(any(), any(), any());
    }

    @Test
    void searchAndDispatch_allResultsBelowThreshold_setsNotFoundStatus_savesHistory() {
        NzbResult lowConfidenceResult = new NzbResult("Random Book EPUB",
                "https://indexer.com/nzb/random.nzb", 1024L, Instant.now(), 1, "TestIndexer");

        when(wantedBookRepository.save(any())).thenReturn(wantedEntity);
        when(indexerRepository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(indexer));
        when(newznabClient.searchBooks(eq(indexer), any())).thenReturn(List.of(lowConfidenceResult));
        when(confidenceScorer.calculateConfidence(any(), any())).thenReturn(30);
        when(confidenceScorer.meetsThreshold(30)).thenReturn(false);
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AcquisitionResult result = service.searchAndDispatch(wantedEntity);

        assertThat(result.found()).isFalse();
        assertThat(wantedEntity.getStatus()).isEqualTo(WantedBookStatus.NOT_FOUND);
        verify(historyRepository, atLeastOnce()).save(any());
        verify(sabnzbdClient, never()).sendNzb(any(), any(), any());
    }

    @Test
    void searchAndDispatch_sabnzbdDispatchFails_setsFailedStatus() {
        when(wantedBookRepository.save(any())).thenReturn(wantedEntity);
        when(indexerRepository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(indexer));
        when(newznabClient.searchBooks(eq(indexer), any())).thenReturn(List.of(goodResult));
        when(confidenceScorer.calculateConfidence(eq(wantedEntity), eq(goodResult))).thenReturn(85);
        when(confidenceScorer.meetsThreshold(85)).thenReturn(true);
        when(clientRepository.findByEnabledTrue()).thenReturn(List.of(sabClient));
        when(sabnzbdClient.sendNzb(any(), any(), any())).thenThrow(new RuntimeException("SABnzbd unreachable"));
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AcquisitionResult result = service.searchAndDispatch(wantedEntity);

        assertThat(result.found()).isFalse();
        assertThat(wantedEntity.getStatus()).isEqualTo(WantedBookStatus.FAILED);
    }

    @Test
    void searchAndDispatch_noEnabledClient_skipsAndStaysWanted() {
        when(clientRepository.existsByEnabledTrue()).thenReturn(false);
        when(wantedBookRepository.save(any())).thenReturn(wantedEntity);
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AcquisitionResult result = service.searchAndDispatch(wantedEntity);

        assertThat(result.found()).isFalse();
        assertThat(wantedEntity.getStatus()).isEqualTo(WantedBookStatus.WANTED);
        verify(sabnzbdClient, never()).sendNzb(any(), any(), any());
        verify(newznabClient, never()).searchBooks(any(), any());
        verify(historyRepository).save(argThat(h -> h.getStatus() == JobHistoryStatus.SKIPPED
                && "No download client configured".equals(h.getNzbTitle())));
    }

    @Test
    void searchAndDispatch_noEnabledIndexer_skipsAndStaysWanted() {
        when(indexerRepository.existsByEnabledTrue()).thenReturn(false);
        when(wantedBookRepository.save(any())).thenReturn(wantedEntity);
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AcquisitionResult result = service.searchAndDispatch(wantedEntity);

        assertThat(result.found()).isFalse();
        assertThat(wantedEntity.getStatus()).isEqualTo(WantedBookStatus.WANTED);
        verify(newznabClient, never()).searchBooks(any(), any());
        verify(historyRepository).save(argThat(h -> h.getStatus() == JobHistoryStatus.SKIPPED
                && "No indexer configured".equals(h.getNzbTitle())));
    }

    @Test
    void searchAndDispatch_noEnabledIndexerOrClient_savesCombinedReason() {
        when(indexerRepository.existsByEnabledTrue()).thenReturn(false);
        when(clientRepository.existsByEnabledTrue()).thenReturn(false);
        when(wantedBookRepository.save(any())).thenReturn(wantedEntity);
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AcquisitionResult result = service.searchAndDispatch(wantedEntity);

        assertThat(result.found()).isFalse();
        assertThat(wantedEntity.getStatus()).isEqualTo(WantedBookStatus.WANTED);
        verify(historyRepository).save(argThat(h -> h.getStatus() == JobHistoryStatus.SKIPPED
                && "No indexer or download client configured".equals(h.getNzbTitle())));
    }
}
