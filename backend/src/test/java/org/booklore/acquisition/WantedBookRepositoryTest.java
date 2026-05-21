package org.booklore.acquisition;

import org.booklore.BookloreApplication;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.enums.WantedBookStatus;
import org.booklore.repository.WantedBookRepository;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = {BookloreApplication.class})
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:wanted-testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.features.oidc-enabled=false",
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false",
        "spring.jpa.properties.hibernate.enable_lazy_load_no_trans=false",
})
@Import(WantedBookRepositoryTest.TestConfig.class)
class WantedBookRepositoryTest {

    @TestConfiguration
    public static class TestConfig {
        @Bean("flyway")
        @Primary
        public Flyway flyway() {
            return mock(Flyway.class);
        }

        @Bean
        @Primary
        public TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }
    }

    @Autowired
    private WantedBookRepository repository;

    @Test
    void findByStatus_returnsMatchingBooks() {
        repository.save(wantedBook("Dune", WantedBookStatus.WANTED));
        repository.save(wantedBook("Foundation", WantedBookStatus.FAILED));
        repository.save(wantedBook("Neuromancer", WantedBookStatus.WANTED));

        List<WantedBookEntity> results = repository.findByStatus(WantedBookStatus.WANTED);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(WantedBookEntity::getTitle).containsExactlyInAnyOrder("Dune", "Neuromancer");
    }

    @Test
    void findByStatusIn_returnsMultipleStatuses() {
        repository.save(wantedBook("Dune", WantedBookStatus.WANTED));
        repository.save(wantedBook("Foundation", WantedBookStatus.FAILED));
        repository.save(wantedBook("Neuromancer", WantedBookStatus.DOWNLOADED));

        List<WantedBookEntity> results = repository.findByStatusIn(
                List.of(WantedBookStatus.WANTED, WantedBookStatus.FAILED));

        assertThat(results).hasSize(2);
        assertThat(results).extracting(WantedBookEntity::getTitle)
                .containsExactlyInAnyOrder("Dune", "Foundation");
    }

    @Test
    void findByStatus_emptyWhenNoMatch() {
        repository.save(wantedBook("Dune", WantedBookStatus.DOWNLOADED));

        List<WantedBookEntity> results = repository.findByStatus(WantedBookStatus.WANTED);

        assertThat(results).isEmpty();
    }

    @Test
    void save_and_findById() {
        WantedBookEntity saved = repository.save(wantedBook("The Hobbit", WantedBookStatus.WANTED));
        assertThat(repository.findById(saved.getId())).isPresent();
    }

    @Test
    void existsByIsbn13_trueWhenPresent() {
        repository.save(wantedBookWithIsbn("Dune", "9780441013593"));
        assertThat(repository.existsByIsbn13("9780441013593")).isTrue();
        assertThat(repository.existsByIsbn13("0000000000000")).isFalse();
    }

    @Test
    void existsByTitleIgnoreCaseAndAuthorIgnoreCase_caseInsensitive() {
        repository.save(wantedBook("Dune", WantedBookStatus.WANTED));
        assertThat(repository.existsByTitleIgnoreCaseAndAuthorIgnoreCase("dune", "test author")).isTrue();
        assertThat(repository.existsByTitleIgnoreCaseAndAuthorIgnoreCase("DUNE", "TEST AUTHOR")).isTrue();
        assertThat(repository.existsByTitleIgnoreCaseAndAuthorIgnoreCase("Dune", "Wrong Author")).isFalse();
    }

    private WantedBookEntity wantedBook(String title, WantedBookStatus status) {
        return WantedBookEntity.builder()
                .title(title)
                .author("Test Author")
                .status(status)
                .addedAt(Instant.now())
                .build();
    }

    private WantedBookEntity wantedBookWithIsbn(String title, String isbn13) {
        return WantedBookEntity.builder()
                .title(title)
                .author("Test Author")
                .isbn13(isbn13)
                .status(WantedBookStatus.WANTED)
                .addedAt(Instant.now())
                .build();
    }
}
