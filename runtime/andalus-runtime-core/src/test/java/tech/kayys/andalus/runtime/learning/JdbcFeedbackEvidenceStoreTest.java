package tech.kayys.andalus.runtime.learning;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.harness.learning.FeedbackEvidenceStore.ReservationStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcFeedbackEvidenceStoreTest {

    private JdbcDataSource dataSource;
    private JdbcFeedbackEvidenceStore store;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:feedback_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        store = new JdbcFeedbackEvidenceStore(dataSource);
        applyMigration();
    }

    @Test
    void migrationIsIdempotent() {
        applyMigration();
        assertThat(store.findOwner("unknown")).isEmpty();
    }

    @Test
    void reservesBatchAndSupportsExactIdempotentRetry() {
        assertThat(store.reserve("signal-1", List.of("evidence-a", "evidence-b")).status())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(store.findOwner("evidence-a")).contains("signal-1");
        assertThat(store.findOwner("evidence-b")).contains("signal-1");

        assertThat(store.reserve("signal-1", List.of("evidence-a", "evidence-b")).status())
                .isEqualTo(ReservationStatus.ALREADY_RESERVED_BY_SAME_SIGNAL);
    }

    @Test
    void rejectsChangedBatchForExistingSignal() {
        store.reserve("signal-1", List.of("evidence-a"));

        assertThat(store.reserve("signal-1", List.of("evidence-b")).status())
                .isEqualTo(ReservationStatus.EVIDENCE_ALREADY_CONSUMED);
        assertThat(store.findOwner("evidence-b")).isEmpty();
    }

    @Test
    void rejectsEvidenceAlreadyOwnedByAnotherSignal() {
        store.reserve("signal-1", List.of("evidence-a"));

        assertThat(store.reserve("signal-2", List.of("evidence-a")).status())
                .isEqualTo(ReservationStatus.EVIDENCE_ALREADY_CONSUMED);
        assertThat(store.findOwner("evidence-a")).contains("signal-1");
    }

    @Test
    void rollsBackEntireBatchWhenAnyEvidenceIsAlreadyReserved() {
        store.reserve("existing-signal", List.of("taken"));

        assertThat(store.reserve("new-signal", List.of("available", "taken")).status())
                .isEqualTo(ReservationStatus.EVIDENCE_ALREADY_CONSUMED);

        assertThat(store.findOwner("available")).isEmpty();
        assertThat(store.findOwner("taken")).contains("existing-signal");
    }

    @Test
    void concurrentClaimsForSameEvidenceHaveExactlyOneWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReservationStatus> first = executor.submit(() -> reserveAfterBarrier(
                    "signal-a", ready, start));
            Future<ReservationStatus> second = executor.submit(() -> reserveAfterBarrier(
                    "signal-b", ready, start));

            ready.await();
            start.countDown();

            List<ReservationStatus> statuses = List.of(first.get(), second.get());
            assertThat(statuses).containsExactlyInAnyOrder(
                    ReservationStatus.RESERVED,
                    ReservationStatus.EVIDENCE_ALREADY_CONSUMED);
            assertThat(store.findOwner("contended-evidence")).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reservationSurvivesAdapterRecreation() {
        store.reserve("signal-1", List.of("persistent-evidence"));

        JdbcFeedbackEvidenceStore recreated = new JdbcFeedbackEvidenceStore(dataSource);
        assertThat(recreated.findOwner("persistent-evidence")).contains("signal-1");
        assertThat(recreated.reserve("signal-1", List.of("persistent-evidence")).status())
                .isEqualTo(ReservationStatus.ALREADY_RESERVED_BY_SAME_SIGNAL);
    }

    private void applyMigration() {
        String resource = "/db/migration/V20261102_01__feedback_evidence_reservations.sql";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing migration resource: " + resource);
            }
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        } catch (IOException | java.sql.SQLException exception) {
            throw new IllegalStateException("Unable to apply feedback evidence test migration", exception);
        }
    }
    private ReservationStatus reserveAfterBarrier(
            String signalId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return store.reserve(signalId, List.of("contended-evidence")).status();
    }
}
