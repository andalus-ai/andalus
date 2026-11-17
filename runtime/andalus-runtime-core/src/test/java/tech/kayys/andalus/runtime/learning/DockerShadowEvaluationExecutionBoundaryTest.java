package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DockerShadowEvaluationExecutionBoundaryTest {

    @Test
    void doesNotAdvertiseContainerIsolationWithoutExplicitDeploymentConfiguration() {
        DockerShadowEvaluationExecutionBoundary boundary = new DockerShadowEvaluationExecutionBoundary();
        assertEquals(ShadowEvaluationExecutionBoundary.IsolationLevel.NONE, boundary.isolationLevel());
    }
}
