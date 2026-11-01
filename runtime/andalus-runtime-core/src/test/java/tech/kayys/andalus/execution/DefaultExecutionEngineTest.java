package tech.kayys.andalus.execution;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.agent.AgentRequest;
import tech.kayys.andalus.agent.AgentResponse;
import tech.kayys.andalus.core.AgentDefinition;
import tech.kayys.andalus.core.DefaultAndalusRuntime;
import tech.kayys.andalus.extension.Id;
import tech.kayys.andalus.extension.Metadata;
import tech.kayys.andalus.identity.ResourceId;
import tech.kayys.andalus.resource.ResourceType;
import tech.kayys.andalus.skill.spi.Skill;
import tech.kayys.andalus.skill.spi.SkillDefinition;
import tech.kayys.andalus.skill.spi.SkillDescriptor;
import tech.kayys.andalus.skill.spi.SkillRegistry;
import tech.kayys.andalus.skill.spi.SkillResult;
import tech.kayys.andalus.workflow.WorkflowDefinition;
import tech.kayys.andalus.workflow.WorkflowEngine;
import tech.kayys.andalus.workflow.WorkflowResult;

import jakarta.enterprise.inject.Instance;
import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DefaultExecutionEngineTest {

    @Test
    void testExecuteWorkflowWithoutProvider() {
        DefaultExecutionEngine engine = new DefaultExecutionEngine();
        engine.workflowEngineInstances = new EmptyInstance<>();

        ExecutionResult result = engine.executeWorkflow(null, null);
        assertNotNull(result);
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().orElse("").contains("No WorkflowEngine provider"));
    }

    @Test
    void testExecuteSkillWithoutProvider() {
        DefaultExecutionEngine engine = new DefaultExecutionEngine();
        engine.skillRegistryInstances = new EmptyInstance<>();

        ExecutionResult result = engine.executeSkill(null, null);
        assertNotNull(result);
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().orElse("").contains("not yet implemented or SkillRegistry not resolvable"));
    }

    @Test
    void testExecuteWorkflowWithDelegation() {
        DefaultExecutionEngine engine = new DefaultExecutionEngine();
        WorkflowResult mockResult = WorkflowResult.success("wf-1", List.of());
        WorkflowEngine mockEngine = new WorkflowEngine() {
            @Override
            public ResourceId id() { return new ResourceId.CustomId(Id.random(), new ResourceType.Execution()); }
            @Override
            public Metadata metadata() { return Metadata.builder().name("mock").build(); }
            @Override
            public ResourceType type() { return new ResourceType.Execution(); }
            @Override
            public WorkflowResult execute(WorkflowDefinition workflow, Map<String, Object> context) {
                return mockResult;
            }
        };
        engine.workflowEngineInstances = new SingleInstance<>(mockEngine);

        AgentRequest req = AgentRequest.builder().content("test prompt").build();
        SimpleGraphExecutionContext context = SimpleGraphExecutionContext.fromRequest(req);

        ExecutionResult result = engine.executeWorkflow(null, context);
        assertNotNull(result);
        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(mockResult, result.getResult());
    }

    @Test
    void testExecuteSkillWithDelegation() {
        DefaultExecutionEngine engine = new DefaultExecutionEngine();
        Skill mockSkill = new Skill() {
            @Override
            public ResourceId id() { return new ResourceId.CustomId(Id.random(), new ResourceType.Execution()); }
            @Override
            public Metadata metadata() { return Metadata.builder().name("mockSkill").build(); }
            @Override
            public ResourceType type() { return new ResourceType.Execution(); }
            @Override
            public SkillDescriptor descriptor() {
                return null;
            }

            @Override
            public SkillResult execute(tech.kayys.andalus.skill.spi.SkillContext context) {
                return new SkillResult() {
                    @Override
                    public Map<String, Object> getOutputs() {
                        return Map.of("key", "value");
                    }

                    @Override
                    public boolean isSuccess() {
                        return true;
                    }
                };
            }
        };

        SkillRegistry mockRegistry = new SkillRegistry() {
            @Override
            public Optional<Skill> findById(String id) {
                return Optional.of(mockSkill);
            }

            @Override
            public List<Skill> findByCategory(String category) {
                return List.of(mockSkill);
            }

            @Override
            public void register(Skill item) {}

            @Override
            public void unregister(ResourceId id) {}

            @Override
            public Optional<Skill> find(ResourceId id) {
                return Optional.of(mockSkill);
            }

            @Override
            public Optional<Skill> findByName(String name) {
                return Optional.of(mockSkill);
            }

            @Override
            public List<Skill> findAll() {
                return List.of(mockSkill);
            }

            @Override
            public List<Skill> findByType(ResourceType type) {
                return List.of(mockSkill);
            }

            @Override
            public List<Skill> findByLabel(String key, String value) {
                return List.of();
            }

            @Override
            public boolean exists(ResourceId id) {
                return true;
            }

            @Override
            public boolean existsByName(String name) {
                return true;
            }

            @Override
            public int count() {
                return 1;
            }

            @Override
            public void clear() {}
        };

        engine.skillRegistryInstances = new SingleInstance<>(mockRegistry);

        ResourceId resId = new ResourceId.CustomId(Id.random(), new ResourceType.Execution());
        Metadata meta = Metadata.builder().name("mockSkillDef").build();
        SkillDefinition def = new SkillDefinition(resId, meta, java.util.Set.of(), Map.of()) {
            @Override
            public SkillDescriptor descriptor() {
                return null;
            }
        };

        ExecutionResult result = engine.executeSkill(def, null);
        assertNotNull(result);
        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(Map.of("key", "value"), result.getResult());
    }

    @Test
    void testDefaultAndalusRuntimeDependencyInversion() {
        DefaultAndalusRuntime runtime = new DefaultAndalusRuntime();
        ExecutionEngine mockEngine = new ExecutionEngine() {
            @Override
            public ResourceId id() { return new ResourceId.CustomId(Id.random(), new ResourceType.Execution()); }
            @Override
            public Metadata metadata() { return Metadata.builder().name("mockEngine").build(); }
            @Override
            public ResourceType type() { return new ResourceType.Execution(); }

            @Override
            public ExecutionResult executeAgent(AgentDefinition agent, GraphExecutionContext context) {
                AgentResponse resp = AgentResponse.builder()
                        .id("exec-123")
                        .content("Hello from mock engine")
                        .success(true)
                        .build();
                return ExecutionResult.builder()
                        .executionId(UUID.randomUUID())
                        .status(ExecutionStatus.COMPLETED)
                        .result(resp)
                        .build();
            }

            @Override
            public ExecutionResult executeWorkflow(WorkflowDefinition workflow, GraphExecutionContext context) {
                return ExecutionResult.failure(UUID.randomUUID(), "unsupported");
            }

            @Override
            public ExecutionResult executeSkill(SkillDefinition skill, GraphExecutionContext context) {
                return ExecutionResult.failure(UUID.randomUUID(), "unsupported");
            }
        };

        // Programmatically configure ExecutionEngine abstraction into runtime (DIP)
        runtime.setExecutionEngine(mockEngine);

        AgentRequest req = AgentRequest.builder().content("ping").build();
        AgentResponse resp = runtime.executeSync(null, req);
        assertNotNull(resp);
        assertTrue(resp.success());
        assertEquals("Hello from mock engine", resp.content());
    }

    // --- Helper CDI Instances for Unit Tests ---

    private static class EmptyInstance<T> implements Instance<T> {
        @Override public Instance<T> select(Annotation... qualifiers) { return this; }
        @Override public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) { return new EmptyInstance<>(); }
        @Override public <U extends T> Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, Annotation... qualifiers) { return new EmptyInstance<>(); }
        @Override public boolean isUnsatisfied() { return true; }
        @Override public boolean isAmbiguous() { return false; }
        @Override public boolean isResolvable() { return false; }
        @Override public void destroy(T instance) {}
        @Override public Handle<T> getHandle() { return null; }
        @Override public Iterable<? extends Handle<T>> handles() { return List.of(); }
        @Override public Iterator<T> iterator() { return List.<T>of().iterator(); }
        @Override public T get() { return null; }
    }

    private static class SingleInstance<T> implements Instance<T> {
        private final T value;
        SingleInstance(T value) { this.value = value; }
        @Override public Instance<T> select(Annotation... qualifiers) { return this; }
        @Override public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) { return new EmptyInstance<>(); }
        @Override public <U extends T> Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, Annotation... qualifiers) { return new EmptyInstance<>(); }
        @Override public boolean isUnsatisfied() { return false; }
        @Override public boolean isAmbiguous() { return false; }
        @Override public boolean isResolvable() { return true; }
        @Override public void destroy(T instance) {}
        @Override public Handle<T> getHandle() { return null; }
        @Override public Iterable<? extends Handle<T>> handles() { return List.of(); }
        @Override public Iterator<T> iterator() { return List.of(value).iterator(); }
        @Override public T get() { return value; }
    }
}
