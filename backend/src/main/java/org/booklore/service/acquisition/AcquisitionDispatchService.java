package org.booklore.service.acquisition;

import org.booklore.model.dto.acquisition.AcquisitionResult;
import org.booklore.model.dto.acquisition.NzbResult;
import org.booklore.model.entity.AcquisitionClientEntity;
import org.booklore.model.entity.AcquisitionIndexerEntity;
import org.booklore.model.entity.AcquisitionJobHistoryEntity;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.enums.JobHistoryStatus;
import org.booklore.model.enums.WantedBookStatus;
import org.booklore.repository.AcquisitionClientRepository;
import org.booklore.repository.AcquisitionIndexerRepository;
import org.booklore.repository.AcquisitionJobHistoryRepository;
import org.booklore.repository.WantedBookRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Runs the search → score → dispatch flow for a single wanted book. Extracted
 * into its own bean so callers (the @Async {@link AcquisitionService#triggerSearch}
 * and the scheduled job) get Spring's @Transactional proxy rather than bypassing
 * it via self-invocation.
 */
@Service
@Slf4j
@AllArgsConstructor
public class AcquisitionDispatchService {

    private final AcquisitionIndexerRepository indexerRepository;
    private final AcquisitionClientRepository clientRepository;
    private final WantedBookRepository wantedBookRepository;
    private final AcquisitionJobHistoryRepository historyRepository;
    private final NewznabClient newznabClient;
    private final SabnzbdClient sabnzbdClient;
    private final ConfidenceScorer confidenceScorer;

    @Transactional
    public AcquisitionResult searchAndDispatch(WantedBookEntity wanted) {
        boolean hasIndexer = indexerRepository.existsByEnabledTrue();
        boolean hasClient = clientRepository.existsByEnabledTrue();
        if (!hasIndexer || !hasClient) {
            String reason;
            if (!hasIndexer && !hasClient) {
                reason = "No indexer or download client configured";
            } else if (!hasIndexer) {
                reason = "No indexer configured";
            } else {
                reason = "No download client configured";
            }
            wanted.setLastCheckedAt(Instant.now());
            wantedBookRepository.save(wanted);
            saveConfigSkipHistory(wanted, reason);
            log.warn("Acquisition pre-flight failed for wanted book id={}: {}", wanted.getId(), reason);
            return AcquisitionResult.notFound(wanted.getId());
        }

        wanted.setStatus(WantedBookStatus.SEARCHING);
        wanted.setLastCheckedAt(Instant.now());
        wantedBookRepository.save(wanted);

        List<String> queries = buildQueries(wanted);

        List<AcquisitionIndexerEntity> indexers = indexerRepository.findByEnabledTrueOrderByPriorityAsc();
        List<NzbResult> allResults = new ArrayList<>();
        for (AcquisitionIndexerEntity indexer : indexers) {
            for (String query : queries) {
                allResults.addAll(newznabClient.searchBooks(indexer, query));
            }
        }

        record ScoredResult(NzbResult result, int score) {}

        Optional<ScoredResult> best = allResults.stream()
                .map(r -> new ScoredResult(r, confidenceScorer.calculateConfidence(wanted, r)))
                .filter(sr -> confidenceScorer.meetsThreshold(sr.score()))
                .max(Comparator.comparingInt(ScoredResult::score));

        if (best.isPresent()) {
            ScoredResult winner = best.get();
            return dispatchToSabnzbd(wanted, winner.result(), winner.score());
        } else {
            markFailed(wanted, allResults);
            return AcquisitionResult.notFound(wanted.getId());
        }
    }

    private List<String> buildQueries(WantedBookEntity wanted) {
        List<String> queries = new ArrayList<>();
        StringBuilder main = new StringBuilder(wanted.getTitle());
        if (wanted.getAuthor() != null && !wanted.getAuthor().isBlank()) {
            main.append(" ").append(wanted.getAuthor());
        }
        queries.add(main.toString());
        if (wanted.getIsbn13() != null && !wanted.getIsbn13().isBlank()) {
            queries.add("isbn:" + wanted.getIsbn13());
        }
        return queries;
    }

    private AcquisitionResult dispatchToSabnzbd(WantedBookEntity wanted, NzbResult winner, int score) {
        List<AcquisitionClientEntity> clients = clientRepository.findByEnabledTrue();
        if (clients.isEmpty()) {
            log.warn("No enabled SABnzbd client — cannot dispatch NZB for wanted book id={}", wanted.getId());
            markDispatchFailed(wanted, winner, score);
            return AcquisitionResult.notFound(wanted.getId());
        }

        AcquisitionClientEntity client = clients.get(0);
        try {
            String jobId = sabnzbdClient.sendNzb(client, winner.downloadUrl(), wanted.getTitle());

            wanted.setStatus(WantedBookStatus.DOWNLOADING);
            wanted.setDownloadId(jobId);
            wantedBookRepository.save(wanted);

            saveHistory(wanted, winner, score, JobHistoryStatus.SENT);

            log.info("Dispatched '{}' to SABnzbd job={} (confidence={})", winner.title(), jobId, score);
            return AcquisitionResult.dispatched(wanted.getId(), winner.title(), score, jobId);
        } catch (Exception e) {
            log.error("SABnzbd dispatch failed for wanted book id={}: {}", wanted.getId(), e.getMessage());
            markDispatchFailed(wanted, winner, score);
            return AcquisitionResult.notFound(wanted.getId());
        }
    }

    private void markDispatchFailed(WantedBookEntity wanted, NzbResult winner, int score) {
        wanted.setRetryCount(wanted.getRetryCount() + 1);
        wanted.setStatus(WantedBookStatus.FAILED);
        wantedBookRepository.save(wanted);
        saveHistory(wanted, winner, score, JobHistoryStatus.FAILED);
    }

    private void markFailed(WantedBookEntity wanted, List<NzbResult> allResults) {
        wanted.setRetryCount(wanted.getRetryCount() + 1);
        wanted.setStatus(WantedBookStatus.NOT_FOUND);
        wantedBookRepository.save(wanted);

        record ScoredResult(NzbResult result, int score) {}
        allResults.stream()
                .map(r -> new ScoredResult(r, confidenceScorer.calculateConfidence(wanted, r)))
                .sorted(Comparator.comparingInt(ScoredResult::score).reversed())
                .limit(3)
                .forEach(sr -> saveHistory(wanted, sr.result(), sr.score(), JobHistoryStatus.SKIPPED));
    }

    private void saveConfigSkipHistory(WantedBookEntity wanted, String reason) {
        AcquisitionJobHistoryEntity history = AcquisitionJobHistoryEntity.builder()
                .wantedBook(wanted)
                .nzbTitle(reason)
                .status(JobHistoryStatus.SKIPPED)
                .attemptedAt(Instant.now())
                .build();
        historyRepository.save(history);
    }

    private void saveHistory(WantedBookEntity wanted, NzbResult result,
                             int confidence, JobHistoryStatus status) {
        AcquisitionJobHistoryEntity history = AcquisitionJobHistoryEntity.builder()
                .wantedBook(wanted)
                .nzbTitle(result.title())
                .nzbUrl(result.downloadUrl())
                .confidence(confidence)
                .status(status)
                .attemptedAt(Instant.now())
                .build();
        historyRepository.save(history);
    }
}
