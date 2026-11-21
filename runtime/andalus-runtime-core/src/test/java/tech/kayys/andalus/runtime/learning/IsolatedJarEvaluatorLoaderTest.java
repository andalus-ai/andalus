package tech.kayys.andalus.runtime.learning;

import org.junit.jupiter.api.Test;
import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class IsolatedJarEvaluatorLoaderTest {

    @Test
    void rejectsArtifactsThatAttemptToShadowRuntimePackages() throws Exception {
        byte[] jar = jarWith("tech/kayys/andalus/runtime/Evil.class");
        var loader = new IsolatedJarEvaluatorLoader();
        try {
            assertThrows(IOException.class, () -> loader.load(jar,
                    new Evaluator.EvaluatorDescriptor("test", "1", Map.of("implementationClass", "example.Evaluator"))));
        } finally {
            loader.close();
        }
    }

    @Test
    void rejectsServiceProviderAndNativeLibraryEntries() throws Exception {
        var loader = new IsolatedJarEvaluatorLoader();
        try {
            assertThrows(IOException.class, () -> loader.load(jarWith("META-INF/services/java.lang.Runnable"),
                    descriptor()));
            assertThrows(IOException.class, () -> loader.load(jarWith("lib/evil.so"), descriptor()));
        } finally {
            loader.close();
        }
    }

    @Test
    void requiresAnExplicitImplementationClassInSignedDescriptorConfiguration() throws Exception {
        var loader = new IsolatedJarEvaluatorLoader();
        try {
            assertThrows(IOException.class, () -> loader.load(jarWith("example/Evaluator.class"),
                    new Evaluator.EvaluatorDescriptor("test", "1", Map.of())));
        } finally {
            loader.close();
        }
    }

    private static Evaluator.EvaluatorDescriptor descriptor() {
        return new Evaluator.EvaluatorDescriptor("test", "1",
                Map.of("implementationClass", "example.Evaluator"));
    }

    private static byte[] jarWith(String entryName) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry(entryName));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
}
