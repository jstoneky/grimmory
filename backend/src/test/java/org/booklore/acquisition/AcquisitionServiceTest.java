package org.booklore.acquisition;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.acquisition.AcquisitionResult;
import org.booklore.model.dto.acquisition.AddToWantedRequest;
import org.booklore.model.dto.acquisition.NzbResult;
import org.booklore.model.dto.acquisition.WantedBookDTO;
import org.booklore.model.entity.AcquisitionClientEntity;
import org.booklore.model.entity.AcquisitionIndexerEntity;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.enums.AcquisitionClientType;
import org.booklore.model.enums.JobHistoryStatus;
import org.booklore.model.enums.WantedBookStatus;
import org.booklore.repository.AcquisitionClientRepository;
import org.booklore.repository.AcquisitionIndexerRepository;
import org.booklore.repository.AcquisitionJobHistoryRepository;
import org.booklore.repository.UserRepository;
import org.booklore.repository.WantedBookRepository;
import org.booklore.service.acquisition.AcquisitionService;
import org.booklore.service.acquisition.ConfidenceScorer;
import org.booklore.service.acquisition.NewznabClient;
import org.booklore.service.acquisition.SabnzbdClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AcquisitionServiceTest {

    @Mock private AcquisitionIndexerRepository indexerRepository;
    @Mock private AcquisitionClientRepository clientRepository;
    @Mock private WantedBookRepository wantedBookRepository;
    @Mock private AcquisitionJobHistoryRepository historyRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthenticationService authenticationService;
    @Mock private NewznabClient newznabClient;
    @Mock private SabnzbdClient sabnzbdClient;
    @Mock private ConfidenceScorer confidenceScorer;

    @InjectMocks
    private AcquisitionService service;

    private WantedBookEntity wantedEntity;
    private AcquisitionIndexerEntity indexer;
    private AcquisitionClientEntity sabClient;
    private NzbResult goodResult;

    @BeforeEach
    void setUp() {
        BookLoreUser mockUser = new BookLoreUser();
        mockUser.setId(1L);
        mockUser.setUsername("testuser");
        lenient().when(authenticationService.getAuthenticatedUser()).thenReturn(mockUser);
        lenient().when(userRepository.findById(1L)).thenReturn(java.util.Optional.empty());
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

    // ─── addToWanted ──────────────────────────────────────────────────────────

    @Test
    void addToWanted_savesEntityAndReturnsDTO() {
        AddToWantedRequest req = new AddToWantedRequest(
                "Dune", "Frank Herbert", "9780441013593", null, "Google", "vol123", null
        );
        when(wantedBookRepository.existsByIsbn13("9780441013593")).thenReturn(false);
        when(wantedBookRepository.save(any())).thenReturn(wantedEntity);

        WantedBookDTO dto = service.addToWanted(req);

        assertThat(dto.title()).isEqualTo("Dune");
        assertThat(dto.status()).isEqualTo(WantedBookStatus.WANTED);
        verify(wantedBookRepository).save(any(WantedBookEntity.class));
    }

    @Test
    void addToWanted_setsAddedByFromCurrentUser() {
        AddToWantedRequest req = new AddToWantedRequest(
                "Dune", "Frank Herbert", "9780441013593", null, "Google", "vol123", null
        );
        org.booklore.model.entity.BookLoreUserEntity userEntity =
                org.booklore.model.entity.BookLoreUserEntity.builder().id(1L).username("testuser").build();
        when(wantedBookRepository.existsByIsbn13("9780441013593")).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(userEntity));
        when(wantedBookRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        org.mockito.ArgumentCaptor<WantedBookEntity> captor =
                org.mockito.ArgumentCaptor.forClass(WantedBookEntity.class);
        service.addToWanted(req);
        verify(wantedBookRepository).save(captor.capture());

        assertThat(captor.getValue().getAddedBy()).isSameAs(userEntity);
    }

    @Test
    void addToWanted_duplicateIsbn13_throws409() {
        AddToWantedRequest req = new AddToWantedRequest(
                "Dune", "Frank Herbert", "9780441013593", null, "Google", "vol123", null
        );
        when(wantedBookRepository.existsByIsbn13("9780441013593")).thenReturn(true);

        assertThatThrownBy(() -> service.addToWanted(req))
                .isInstanceOf(APIException.class)
                .satisfies(e -> assertThat(((APIException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(wantedBookRepository, never()).save(any());
    }

    @Test
    void addToWanted_duplicateTitleAuthor_throws409() {
        AddToWantedRequest req = new AddToWantedRequest(
                "Dune", "Frank Herbert", null, null, null, null, null
        );
        when(wantedBookRepository.existsByTitleIgnoreCaseAndAuthorIgnoreCase("Dune", "Frank Herbert"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.addToWanted(req))
                .isInstanceOf(APIException.class)
                .satisfies(e -> assertThat(((APIException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ─── removeWanted ─────────────────────────────────────────────────────────

    @Test
    void removeWanted_notFound_throws404() {
        when(wantedBookRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.removeWanted(99L))
                .isInstanceOf(APIException.class)
                .satisfies(e -> assertThat(((APIException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void removeWanted_found_deletes() {
        when(wantedBookRepository.existsById(1L)).thenReturn(true);

        service.removeWanted(1L);

        verify(wantedBookRepository).deleteById(1L);
    }

    // ─── searchAndDispatch — confident match ──────────────────────────────────

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

    // ─── searchAndDispatch — no results ───────────────────────────────────────

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

    // ─── searchAndDispatch — results below threshold ──────────────────────────

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

    // ─── triggerSearch ────────────────────────────────────────────────────────

    @Test
    void triggerSearch_notFound_throws404() {
        when(wantedBookRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triggerSearch(99L))
                .isInstanceOf(APIException.class)
                .satisfies(e -> assertThat(((APIException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void triggerSearch_found_invokesSearchAndDispatch() {
        when(wantedBookRepository.findById(1L)).thenReturn(Optional.of(wantedEntity));
        when(wantedBookRepository.save(any())).thenReturn(wantedEntity);
        when(indexerRepository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(indexer));
        when(newznabClient.searchBooks(any(), any())).thenReturn(List.of());

        service.triggerSearch(1L);

        verify(newznabClient, atLeastOnce()).searchBooks(any(), any());
    }

    // ─── getJobHistory ────────────────────────────────────────────────────────

    @Test
    void getJobHistory_wantedBookNotFound_throws404() {
        when(wantedBookRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.getJobHistory(99L))
                .isInstanceOf(APIException.class)
                .satisfies(e -> assertThat(((APIException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getJobHistory_returnsEmptyList() {
        when(wantedBookRepository.existsById(1L)).thenReturn(true);
        when(historyRepository.findByWantedBookIdOrderByAttemptedAtDesc(1L)).thenReturn(List.of());

        var result = service.getJobHistory(1L);

        assertThat(result).isEmpty();
    }

    @Test
    void getJobHistory_returnsMappedDTOs() {
        org.booklore.model.entity.AcquisitionJobHistoryEntity historyEntity =
                org.booklore.model.entity.AcquisitionJobHistoryEntity.builder()
                        .id(10L)
                        .wantedBook(wantedEntity)
                        .nzbTitle("Dune Frank Herbert EPUB")
                        .confidence(85)
                        .status(org.booklore.model.enums.JobHistoryStatus.SENT)
                        .attemptedAt(java.time.Instant.now())
                        .build();
        when(wantedBookRepository.existsById(1L)).thenReturn(true);
        when(historyRepository.findByWantedBookIdOrderByAttemptedAtDesc(1L))
                .thenReturn(List.of(historyEntity));

        var result = service.getJobHistory(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nzbTitle()).isEqualTo("Dune Frank Herbert EPUB");
        assertThat(result.get(0).confidence()).isEqualTo(85);
    }

    // ─── getWantedBooks ───────────────────────────────────────────────────────

    @Test
    void getWantedBooks_returnsMappedList() {
        when(wantedBookRepository.findAll()).thenReturn(List.of(wantedEntity));

        List<WantedBookDTO> result = service.getWantedBooks();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Dune");
    }
}
