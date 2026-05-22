package org.booklore.service.bookdrop;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.MetadataRefreshService;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.util.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.booklore.model.entity.BookdropFileEntity.Status.PENDING_REVIEW;
import static org.booklore.util.FileService.truncate;

@Slf4j
@AllArgsConstructor
@Service
public class BookdropMetadataService {

    private static final Pattern ISBN_NON_DIGIT_PATTERN = Pattern.compile("[^0-9Xx]");

    private final BookdropFileRepository bookdropFileRepository;
    private final AppSettingService appSettingService;
    private final ObjectMapper objectMapper;
    private final MetadataExtractorFactory metadataExtractorFactory;
    private final MetadataRefreshService metadataRefreshService;
    private final FileService fileService;

    @Transactional
    public BookdropFileEntity attachInitialMetadata(Long bookdropFileId) throws JacksonException {
        BookdropFileEntity entity = getOrThrow(bookdropFileId);
        BookMetadata initial = extractInitialMetadata(entity);
        if (initial == null) {
            log.warn("Metadata extraction returned null for file: {}. Using empty metadata as fallback.", entity.getFileName());
            initial = BookMetadata.builder().build();
        }
        if (initial.getTitle() == null || initial.getTitle().isBlank()) {
            log.warn("Metadata extraction returned empty title for file: {}. Using filename as fallback.", entity.getFileName());
            initial.setTitle(FilenameUtils.getBaseName(entity.getFileName()));
        }
        extractAndSaveCover(entity);
        String initialJson = objectMapper.writeValueAsString(initial);
        entity.setOriginalMetadata(initialJson);
        entity.setUpdatedAt(Instant.now());
        return bookdropFileRepository.save(entity);
    }

    @Transactional
    public BookdropFileEntity attachFetchedMetadata(Long bookdropFileId) throws JacksonException {
        BookdropFileEntity entity = getOrThrow(bookdropFileId);

        AppSettings appSettings = appSettingService.getAppSettings();

        MetadataRefreshOptions refreshOptions = appSettings.getDefaultMetadataRefreshOptions();

        BookMetadata initial = objectMapper.readValue(entity.getOriginalMetadata(), BookMetadata.class);

        if (!hasSearchableMetadata(initial, entity)) {
            log.info("Skipping online metadata fetch for '{}' — no reliable search data (title derived from filename, no ISBN or ASIN).", entity.getFileName());
            entity.setStatus(PENDING_REVIEW);
            entity.setUpdatedAt(Instant.now());
            return bookdropFileRepository.save(entity);
        }

        List<MetadataProvider> providers = metadataRefreshService.prepareProviders(refreshOptions);
        Book book = Book.builder()
                .metadata(initial)
                .build();

        if (providers.contains(MetadataProvider.GoodReads)) {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(250, 1250));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        Map<MetadataProvider, BookMetadata> metadataMap = metadataRefreshService.fetchMetadataForBook(providers, book);
        BookMetadata fetchedMetadata = metadataRefreshService.buildFetchMetadata(initial, book.getId(), refreshOptions, metadataMap);
        String fetchedJson = objectMapper.writeValueAsString(fetchedMetadata);

        entity.setFetchedMetadata(fetchedJson);
        entity.setStatus(PENDING_REVIEW);
        entity.setUpdatedAt(Instant.now());

        return bookdropFileRepository.save(entity);
    }

    private boolean hasSearchableMetadata(BookMetadata metadata, BookdropFileEntity entity) {
        if (hasAnyKnownIdentifier(metadata)) {
            return true;
        }
        String title = metadata.getTitle();
        String filenameFallback = FilenameUtils.getBaseName(entity.getFileName());
        return title != null && !title.isBlank()
                && !title.strip().equalsIgnoreCase(filenameFallback.strip());
    }

    private boolean hasAnyKnownIdentifier(BookMetadata m) {
        // Keep in sync with identifier fields in BookMetadata when new providers are added.
        return Stream.of(
                m.getIsbn13(), m.getIsbn10(), m.getAsin(),
                m.getGoodreadsId(), m.getGoogleId(),
                m.getHardcoverId(), m.getHardcoverBookId(),
                m.getComicvineId(), m.getDoubanId(),
                m.getLubimyczytacId(), m.getRanobedbId(), m.getAudibleId()
        ).anyMatch(id -> id != null && !id.isBlank());
    }

    private BookdropFileEntity getOrThrow(Long id) {
        return bookdropFileRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Bookdrop file not found: " + id));
    }

    private BookMetadata cleanInitialMetadata(BookMetadata extracted) {
        if (extracted == null) {
            return null;
        }

        // Created a builder from `extracted` to perform a shallow clone
        // where we overwrite only truncated fields.

        String[] normalizedIsbns = normalizeIsbns(extracted.getIsbn10(), extracted.getIsbn13());

        return extracted.toBuilder()

                // Basic Fields
                .title(truncate(extracted.getTitle(), 1000))
                .subtitle(truncate(extracted.getSubtitle(), 1000))
                .description(truncate(extracted.getDescription(), 5000))
                .publisher(truncate(extracted.getPublisher(), 1000))
                .publishedDate(extracted.getPublishedDate())
                .seriesName(truncate(extracted.getSeriesName(), 1000))
                .language(truncate(extracted.getLanguage(), 10))

                // ISBN — validate & migrate. Drops malformed values rather than
                // truncating them (truncating a 13-digit ISBN to 10 chars produces
                // a garbage identifier that 404s every metadata provider).
                .isbn10(normalizedIsbns[0])
                .isbn13(normalizedIsbns[1])

                // External IDs
                .asin(truncate(extracted.getAsin(), 10))
                .audibleId(truncate(extracted.getAudibleId(), 10))
                .goodreadsId(truncate(extracted.getGoodreadsId(), 100))
                .hardcoverId(truncate(extracted.getHardcoverId(), 100))
                .hardcoverBookId(truncate(extracted.getHardcoverBookId(), 100))
                .googleId(truncate(extracted.getGoogleId(), 100))
                .comicvineId(truncate(extracted.getComicvineId(), 100))
                .lubimyczytacId(truncate(extracted.getLubimyczytacId(), 100))
                .ranobedbId(truncate(extracted.getRanobedbId(), 100))
                .doubanId(truncate(extracted.getDoubanId(), 100))

                .build();
    }

    /**
     * Returns [isbn10, isbn13] with separators stripped, lengths validated, and
     * 13-digit values that landed in the isbn10 slot promoted to isbn13. Any
     * value that can't be coerced to a 10- or 13-digit identifier is dropped.
     */
    private String[] normalizeIsbns(String rawIsbn10, String rawIsbn13) {
        String clean10 = cleanIsbn(rawIsbn10);
        String clean13 = cleanIsbn(rawIsbn13);

        // Promote misfiled values to the correct field
        if (clean13 != null && clean13.length() == 10 && clean10 == null) {
            clean10 = clean13;
            clean13 = null;
        }
        if (clean10 != null && clean10.length() == 13 && clean13 == null) {
            clean13 = clean10;
            clean10 = null;
        }

        // Drop malformed values
        if (clean10 != null && clean10.length() != 10) {
            clean10 = null;
        }
        if (clean13 != null && clean13.length() != 13) {
            clean13 = null;
        }

        return new String[]{clean10, clean13};
    }

    private String cleanIsbn(String input) {
        if (input == null || input.isBlank()) return null;
        String cleaned = ISBN_NON_DIGIT_PATTERN.matcher(input).replaceAll("").toUpperCase();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private BookMetadata extractInitialMetadata(BookdropFileEntity entity) {
        File file = new File(entity.getFilePath());
        BookFileExtension fileExt = BookFileExtension.fromFileName(file.getName())
            .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException("Unsupported file extension"));
        return cleanInitialMetadata(metadataExtractorFactory.extractMetadata(fileExt, file));
    }

    private void extractAndSaveCover(BookdropFileEntity entity) {
        File file = new File(entity.getFilePath());
        BookFileExtension fileExt = BookFileExtension.fromFileName(file.getName())
            .orElseThrow(() -> ApiError.INVALID_FILE_FORMAT.createException("Unsupported file extension"));
        byte[] coverBytes = metadataExtractorFactory.extractCover(fileExt, file);
        if (coverBytes != null) {
            try {
                FileService.saveImage(coverBytes, fileService.getTempBookdropCoverImagePath(entity.getId()));
            } catch (IOException e) {
                log.warn("Failed to save extracted cover for file: {}", entity.getFilePath(), e);
            }
        }
    }
}
