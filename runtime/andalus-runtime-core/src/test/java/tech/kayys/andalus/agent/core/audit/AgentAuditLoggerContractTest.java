package tech.kayys.andalus.agent.core.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAuditLoggerContractTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsSynchronousMemoryEventsWithSanitizedAttributes() {
        AgentAuditLogger logger = AgentAuditLogger.memoryBased();
        String prompt = "x".repeat(120);

        logger.logAgentExecution(null, null, prompt, -50, true);

        List<AgentAuditLogger.AuditEvent> events = logger.getEvents();
        assertEquals(1, events.size());
        AgentAuditLogger.AuditEvent event = events.get(0);

        assertEquals("agent.execution", event.eventType());
        assertEquals("unknown", event.component());
        assertFalse(event.attributes().containsKey("strategy"));
        assertEquals(0L, event.attributes().get("duration_ms"));
        assertEquals(true, event.attributes().get("success"));
        assertEquals(103, String.valueOf(event.attributes().get("prompt")).length());
        assertThrows(UnsupportedOperationException.class, () -> event.attributes().put("later", "nope"));
    }

    @Test
    void asyncMemoryLoggerFlushesSubmittedEvents() {
        try (AgentAuditLogger logger = AgentAuditLogger.memoryBasedAsync()) {
            logger.logToolExecution("search", "{\"query\":\"docs\"}", 42, true);
            logger.logInferenceCall("aqli", "model-a", 100, false);

            logger.flush();

            List<AgentAuditLogger.AuditEvent> events = logger.getEvents();
            assertEquals(2, events.size());
            assertEquals("tool.execution", events.get(0).eventType());
            assertEquals("inference.call", events.get(1).eventType());
        }
    }

    @Test
    void auditEventSnapshotsNestedAttributes() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("amount", 5000);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("nested", nested);
        attributes.put("steps", List.of(nested));

        AgentAuditLogger.AuditEvent event = new AgentAuditLogger.AuditEvent(
                Instant.parse("2026-01-02T03:04:05Z"),
                " custom.event ",
                " component-a ",
                attributes);
        nested.put("amount", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> copiedNested = (Map<String, Object>) event.attributes().get("nested");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) event.attributes().get("steps");

        assertEquals("custom.event", event.eventType());
        assertEquals("component-a", event.component());
        assertEquals(5000, copiedNested.get("amount"));
        assertEquals(5000, steps.get(0).get("amount"));
        assertThrows(UnsupportedOperationException.class, () -> copiedNested.put("later", "nope"));
        assertThrows(UnsupportedOperationException.class, () -> steps.get(0).put("later", "nope"));
    }

    @Test
    void fileBasedLoggerCreatesParentAndWritesLine() throws IOException {
        Path logFile = tempDir.resolve("nested").resolve("audit.log");
        AgentAuditLogger logger = AgentAuditLogger.fileBased(logFile);

        logger.logError("agent-core", "Timeout", "Inference timed out");

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("agent-core.error"));
        assertTrue(lines.get(0).contains("Timeout"));
    }

    @Test
    void memoryEventsCanBeCleared() {
        AgentAuditLogger logger = AgentAuditLogger.memoryBased();
        logger.logSecurityEvent(null, "details", null);

        assertEquals(1, logger.getEvents().size());
        logger.clearEvents();

        assertTrue(logger.getEvents().isEmpty());
    }

    @Test
    void sinkFailuresDoNotEscapeRecorder() {
        AgentAuditLogger logger = AgentAuditLogger.sinkBased(event -> {
            throw new IllegalStateException("sink down");
        });

        assertDoesNotThrow(() -> logger.logMemoryOperation("write", "vector", "key", 5));
    }

    @Test
    void customAsyncSinkCanBeFlushed() {
        List<AgentAuditLogger.AuditEvent> events = new CopyOnWriteArrayList<>();

        try (AgentAuditLogger logger = AgentAuditLogger.sinkBasedAsync(events::add)) {
            logger.logMemoryOperation("read", "short", "thread", 12);
            logger.flush();
        }

        assertEquals(1, events.size());
        assertEquals("memory.operation", events.get(0).eventType());
    }
}
