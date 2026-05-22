package org.booklore.service.acquisition;

import org.booklore.exception.ApiError;
import org.booklore.model.dto.acquisition.AddToWantedRequest;
import org.booklore.model.dto.acquisition.JobHistoryDTO;
import org.booklore.model.dto.acquisition.WantedBookDTO;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.enums.WantedBookStatus;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.repository.AcquisitionJobHistoryRepository;
import org.booklore.repository.UserRepository;
import org.booklore.repository.WantedBookRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@AllArgsConstructor
public class AcquisitionService {

    private final WantedBookRepository wantedBookRepository;
    private final AcquisitionJobHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final AuthenticationService authenticationService;
    private final AcquisitionDispatchService dispatchService;

    @Transactional
    public WantedBookDTO addToWanted(AddToWantedRequest request) {
        if (request.isbn13() != null && !request.isbn13().isBlank()) {
            if (wantedBookRepository.existsByIsbn13(request.isbn13())) {
                throw ApiError.DUPLICATE_WANTED_BOOK.createException(request.isbn13());
            }
        } else if (request.title() != null && request.author() != null) {
            if (wantedBookRepository.existsByTitleIgnoreCaseAndAuthorIgnoreCase(
                    request.title(), request.author())) {
                throw ApiError.DUPLICATE_WANTED_BOOK.createException(request.title());
            }
        }

        BookLoreUser currentUser = authenticationService.getAuthenticatedUser();
        var addedByEntity = userRepository.findById(currentUser.getId()).orElse(null);

        WantedBookEntity entity = WantedBookEntity.builder()
                .title(request.title())
                .author(request.author())
                .isbn13(request.isbn13())
                .isbn10(request.isbn10())
                .provider(request.provider())
                .providerBookId(request.providerBookId())
                .thumbnailUrl(request.thumbnailUrl())
                .status(WantedBookStatus.WANTED)
                .addedAt(Instant.now())
                .addedBy(addedByEntity)
                .build();

        WantedBookEntity saved = wantedBookRepository.save(entity);
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<WantedBookDTO> getWantedBooks() {
        return wantedBookRepository.findAll().stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional
    public void removeWanted(Long id) {
        if (!wantedBookRepository.existsById(id)) {
            throw ApiError.WANTED_BOOK_NOT_FOUND.createException(id);
        }
        wantedBookRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<JobHistoryDTO> getJobHistory(Long wantedBookId) {
        if (!wantedBookRepository.existsById(wantedBookId)) {
            throw ApiError.WANTED_BOOK_NOT_FOUND.createException(wantedBookId);
        }
        return historyRepository.findByWantedBookIdOrderByAttemptedAtDesc(wantedBookId).stream()
                .map(e -> new JobHistoryDTO(
                        e.getId(),
                        e.getWantedBook().getId(),
                        e.getIndexerId(),
                        e.getNzbTitle(),
                        e.getConfidence(),
                        e.getStatus(),
                        e.getAttemptedAt()
                ))
                .toList();
    }

    @Async
    public void triggerSearch(Long wantedBookId) {
        WantedBookEntity wanted = wantedBookRepository.findById(wantedBookId)
                .orElseThrow(() -> ApiError.WANTED_BOOK_NOT_FOUND.createException(wantedBookId));
        if (wanted.getStatus() == WantedBookStatus.SEARCHING || wanted.getStatus() == WantedBookStatus.DOWNLOADING) {
            log.info("Book id={} already in status {}, skipping trigger", wantedBookId, wanted.getStatus());
            return;
        }
        // Call through the injected bean so Spring's @Transactional proxy applies.
        dispatchService.searchAndDispatch(wanted);
    }

    private WantedBookDTO toDTO(WantedBookEntity e) {
        return new WantedBookDTO(
                e.getId(), e.getTitle(), e.getAuthor(), e.getIsbn13(), e.getIsbn10(),
                e.getProvider(), e.getProviderBookId(), e.getThumbnailUrl(),
                e.getStatus(), e.getLastCheckedAt(), e.getDownloadId(),
                e.getAddedBy() != null ? e.getAddedBy().getId() : null,
                e.getAddedAt()
        );
    }
}
