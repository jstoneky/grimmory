package org.booklore.acquisition;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.acquisition.AddToWantedRequest;
import org.booklore.model.dto.acquisition.WantedBookDTO;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.enums.WantedBookStatus;
import org.booklore.repository.AcquisitionJobHistoryRepository;
import org.booklore.repository.UserRepository;
import org.booklore.repository.WantedBookRepository;
import org.booklore.service.acquisition.AcquisitionDispatchService;
import org.booklore.service.acquisition.AcquisitionService;
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

    @Mock private WantedBookRepository wantedBookRepository;
    @Mock private AcquisitionJobHistoryRepository historyRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthenticationService authenticationService;
    @Mock private AcquisitionDispatchService dispatchService;

    @InjectMocks
    private AcquisitionService service;

    private WantedBookEntity wantedEntity;

    @BeforeEach
    void setUp() {
        BookLoreUser mockUser = new BookLoreUser();
        mockUser.setId(1L);
        mockUser.setUsername("testuser");
        lenient().when(authenticationService.getAuthenticatedUser()).thenReturn(mockUser);
        lenient().when(userRepository.findById(1L)).thenReturn(java.util.Optional.empty());

        wantedEntity = WantedBookEntity.builder()
                .id(1L)
                .title("Dune")
                .author("Frank Herbert")
                .isbn13("9780441013593")
                .status(WantedBookStatus.WANTED)
                .addedAt(Instant.now())
                .build();
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

    // ─── triggerSearch ────────────────────────────────────────────────────────

    @Test
    void triggerSearch_notFound_throws404() {
        when(wantedBookRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triggerSearch(99L))
                .isInstanceOf(APIException.class)
                .satisfies(e -> assertThat(((APIException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(dispatchService, never()).searchAndDispatch(any());
    }

    @Test
    void triggerSearch_found_delegatesToDispatchService() {
        when(wantedBookRepository.findById(1L)).thenReturn(Optional.of(wantedEntity));

        service.triggerSearch(1L);

        verify(dispatchService).searchAndDispatch(wantedEntity);
    }

    @Test
    void triggerSearch_alreadySearching_skipsDispatch() {
        wantedEntity.setStatus(WantedBookStatus.SEARCHING);
        when(wantedBookRepository.findById(1L)).thenReturn(Optional.of(wantedEntity));

        service.triggerSearch(1L);

        verify(dispatchService, never()).searchAndDispatch(any());
    }

    @Test
    void triggerSearch_alreadyDownloading_skipsDispatch() {
        wantedEntity.setStatus(WantedBookStatus.DOWNLOADING);
        when(wantedBookRepository.findById(1L)).thenReturn(Optional.of(wantedEntity));

        service.triggerSearch(1L);

        verify(dispatchService, never()).searchAndDispatch(any());
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
