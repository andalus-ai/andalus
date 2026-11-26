package tech.kayys.andalus.execution.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.AgentContext;
import tech.kayys.andalus.execution.ExecutionCheckpoint;
import tech.kayys.andalus.execution.ExecutionCheckpointId;
import tech.kayys.andalus.execution.ExecutionPhase;
import tech.kayys.andalus.execution.ExecutionStatus;

class ExecutionCheckpointEnvelopeCodecTest {

    @Test
    void roundTripsLifecyclePositionContextAndOwnershipMetadata() {
        CheckpointValueCodec values = new CheckpointValueCodec();
        ArtifactCheckpointCodec artifacts = new ArtifactCheckpointCodec(values);
        AgentContextCheckpointCodec contexts = new AgentContextCheckpointCodec(
                values, artifacts,
                new AgentMessageCheckpointCodec(values, artifacts),
                new ContextDataCheckpointCodec(values),
                new OutputResultCheckpointCodec(values, artifacts));
        ExecutionCheckpointEnvelopeCodec codec = new ExecutionCheckpointEnvelopeCodec(contexts, values);

        AgentContext context = AgentContext.builder()
                .sessionId("session-42")
                .tenantId("tenant-7")
                .build();
        Instant createdAt = Instant.parse("2026-11-25T10:15:30Z");
        ExecutionCheckpoint checkpoint = new ExecutionCheckpoint(
                new ExecutionCheckpointId("checkpoint-123"),
                "execution-456",
                ExecutionStatus.PAUSED,
                ExecutionPhase.TOOL,
                3,
                11,
                context,
                createdAt,
                Map.of("tenantId", "tenant-7", "userId", "user-9",
                        "principalType", "USER", "principalId", "user-9"));

        ExecutionCheckpoint decoded = codec.decode(codec.encode(checkpoint));

        assertThat(decoded.checkpointId()).isEqualTo(checkpoint.checkpointId());
        assertThat(decoded.executionId()).isEqualTo("execution-456");
        assertThat(decoded.status()).isEqualTo(ExecutionStatus.PAUSED);
        assertThat(decoded.phase()).isEqualTo(ExecutionPhase.TOOL);
        assertThat(decoded.attempt()).isEqualTo(3);
        assertThat(decoded.iteration()).isEqualTo(11);
        assertThat(decoded.createdAt()).isEqualTo(createdAt);
        assertThat(decoded.context().sessionId()).isEqualTo("session-42");
        assertThat(decoded.context().tenantId()).isEqualTo("tenant-7");
        assertThat(decoded.metadata()).containsEntry("tenantId", "tenant-7")
                .containsEntry("principalType", "USER")
                .containsEntry("principalId", "user-9");
    }

    @Test
    void rejectsUnknownEnvelopeSchemaVersion() {
        CheckpointValueCodec values = new CheckpointValueCodec();
        ArtifactCheckpointCodec artifacts = new ArtifactCheckpointCodec(values);
        AgentContextCheckpointCodec contexts = new AgentContextCheckpointCodec(
                values, artifacts,
                new AgentMessageCheckpointCodec(values, artifacts),
                new ContextDataCheckpointCodec(values),
                new OutputResultCheckpointCodec(values, artifacts));
        ExecutionCheckpointEnvelopeCodec codec = new ExecutionCheckpointEnvelopeCodec(contexts, values);
        ExecutionCheckpoint checkpoint = ExecutionCheckpoint.of(
                "execution-1", ExecutionStatus.PENDING, ExecutionPhase.INPUT, 0, 0,
                AgentContext.builder().build());
        ExecutionCheckpointEnvelopeCodec.ExecutionCheckpointValue encoded = codec.encode(checkpoint);
        ExecutionCheckpointEnvelopeCodec.ExecutionCheckpointValue unsupported =
                new ExecutionCheckpointEnvelopeCodec.ExecutionCheckpointValue(
                        77, encoded.checkpointId(), encoded.executionId(), encoded.status(), encoded.phase(),
                        encoded.attempt(), encoded.iteration(), encoded.context(), encoded.createdAt(),
                        encoded.metadata());

        assertThatThrownBy(() -> codec.decode(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported execution checkpoint envelope schema version");
    }
}
