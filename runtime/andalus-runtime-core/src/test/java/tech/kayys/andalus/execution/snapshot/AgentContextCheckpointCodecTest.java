package tech.kayys.andalus.execution.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.AgentContext;
import tech.kayys.andalus.agent.AgentContextPhase;
import tech.kayys.andalus.agent.AgentContextState;
import tech.kayys.andalus.extension.Id;
import tech.kayys.andalus.identity.ResourceId;

class AgentContextCheckpointCodecTest {

    @Test
    void roundTripsAggregateStateAndBoundedHistory() {
        Instant created = Instant.parse("2026-01-02T03:04:05Z");
        Instant updated = Instant.parse("2026-01-02T03:05:06Z");
        AgentContext historical = AgentContext.builder()
                .id(new ResourceId.AgentId(Id.fromString("11111111-1111-1111-1111-111111111111")))
                .sessionId("session-1")
                .createdAt(created.minusSeconds(60))
                .updatedAt(created.minusSeconds(30))
                .build();

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("query", "checkpoint me");
        variables.put("attempt", 7L);
        variables.put("nullable", null);
        variables.put("nested", Map.of("enabled", true));

        AgentContext original = AgentContext.builder()
                .id(new ResourceId.AgentId(Id.fromString("22222222-2222-2222-2222-222222222222")))
                .sessionId("session-1")
                .tenantId("tenant-1")
                .namespace("production")
                .variables(variables)
                .attributes(Map.of("source", "test"))
                .state(AgentContextState.PAUSED)
                .createdAt(created)
                .updatedAt(updated)
                .version(19)
                .metadata(Map.of("traceId", "trace-1"))
                .currentPhase(AgentContextPhase.TOOLS)
                .startTime(1_700_000_000_000L)
                .elapsedTime(12_345L)
                .phaseTimings(Map.of("INPUT", 120L, "TOOLS", 900L))
                .completedPhases(new java.util.LinkedHashSet<>(List.of("INPUT", "CONTEXT")))
                .history(List.of(historical))
                .build();

        AgentContextCheckpointCodec codec = codec(4);
        AgentContextCheckpointCodec.AgentContextValue encoded = codec.encode(original);
        AgentContext decoded = codec.decode(encoded);

        assertThat(decoded.id()).isEqualTo(original.id());
        assertThat(decoded.sessionId()).isEqualTo("session-1");
        assertThat(decoded.tenantId()).isEqualTo("tenant-1");
        assertThat(decoded.namespace()).isEqualTo("production");
        assertThat(decoded.state()).isEqualTo(AgentContextState.PAUSED);
        assertThat(decoded.currentPhase()).isEqualTo(AgentContextPhase.TOOLS);
        assertThat(decoded.createdAt()).isEqualTo(created);
        assertThat(decoded.updatedAt()).isEqualTo(updated);
        assertThat(decoded.version()).isEqualTo(19);
        assertThat(decoded.startTime()).isEqualTo(1_700_000_000_000L);
        assertThat(decoded.elapsedTime()).isEqualTo(12_345L);
        assertThat(decoded.contextMetadata()).containsEntry("traceId", "trace-1");
        assertThat(decoded.phaseTimings()).containsEntry("INPUT", 120L).containsEntry("TOOLS", 900L);
        assertThat(decoded.completedPhases()).containsExactly("INPUT", "CONTEXT");
        assertThat(decoded.variables()).containsEntry("nullable", null)
                .containsEntry("query", "checkpoint me")
                .containsEntry("attempt", new java.math.BigInteger("7"));
        assertThat(decoded.history()).hasSize(1);
        assertThat(decoded.history().getFirst().id()).isEqualTo(historical.id());
        assertThat(encoded.historyTruncated()).isFalse();
    }

    @Test
    void marksHistoryTruncationAndRefusesLossyRestore() {
        AgentContext historical = AgentContext.builder()
                .id(new ResourceId.AgentId(Id.fromString("33333333-3333-3333-3333-333333333333")))
                .build();
        AgentContext original = AgentContext.builder()
                .id(new ResourceId.AgentId(Id.fromString("44444444-4444-4444-4444-444444444444")))
                .history(List.of(historical))
                .build();

        AgentContextCheckpointCodec codec = codec(0);
        AgentContextCheckpointCodec.AgentContextValue encoded = codec.encode(original);

        assertThat(encoded.history()).isEmpty();
        assertThat(encoded.historyTruncated()).isTrue();
        assertThat(encoded.omittedHistoryEntries()).isEqualTo(1);
        assertThatThrownBy(() -> codec.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("history was truncated");
    }

    @Test
    void rejectsUnknownSchemaVersion() {
        AgentContextCheckpointCodec.AgentContextValue value = codec(2).encode(AgentContext.builder().build());
        AgentContextCheckpointCodec.AgentContextValue unsupported = new AgentContextCheckpointCodec.AgentContextValue(
                99, value.idType(), value.idValue(), value.sessionId(), value.tenantId(), value.namespace(),
                value.request(), value.response(), value.variables(), value.attributes(), value.artifacts(),
                value.contextData(), value.outputResult(), value.state(), value.createdAt(), value.updatedAt(),
                value.version(), value.metadata(), value.currentPhase(), value.startTime(), value.elapsedTime(),
                value.phaseTimings(), value.completedPhases(), value.history(), value.historyTruncated(),
                value.omittedHistoryEntries());

        assertThatThrownBy(() -> codec(2).decode(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported AgentContext checkpoint schema version");
    }

    private AgentContextCheckpointCodec codec(int maxHistoryDepth) {
        CheckpointValueCodec values = new CheckpointValueCodec();
        ArtifactCheckpointCodec artifacts = new ArtifactCheckpointCodec(values);
        return new AgentContextCheckpointCodec(values, artifacts,
                new AgentMessageCheckpointCodec(values, artifacts),
                new ContextDataCheckpointCodec(values),
                new OutputResultCheckpointCodec(values, artifacts),
                maxHistoryDepth);
    }
}
