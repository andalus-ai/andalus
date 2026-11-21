package tech.kayys.andalus.runtime.learning;

import tech.kayys.andalus.harness.evaluation.Evaluator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Loads an already signature- and digest-verified, dependency-free evaluator JAR.
 *
 * This classloader is a namespace boundary, NOT a security sandbox. The worker must still run
 * inside the configured OS-enforced container/microVM with no application credentials, no
 * network, bounded resources, and no host mounts. Artifacts may reference only JDK classes and
 * the small Evaluator API; all other dependencies must be shaded into the artifact JAR.
 */
public final class IsolatedJarEvaluatorLoader
        implements ShadowEvaluationWorkerEngine.VerifiedArtifactLoader, AutoCloseable {

    private static final int MAX_JAR_BYTES = 7 * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;
    private final Set<URLClassLoader> loaders = new HashSet<>();
    private final Set<Path> temporaryFiles = new HashSet<>();
    private boolean closed;

    @Override
    public synchronized Evaluator load(byte[] verifiedArtifactBytes,
            Evaluator.EvaluatorDescriptor descriptor) throws Exception {
        if (closed) throw new IllegalStateException("artifact loader is closed");
        if (verifiedArtifactBytes == null || verifiedArtifactBytes.length == 0
                || verifiedArtifactBytes.length > MAX_JAR_BYTES || descriptor == null) {
            throw new IOException("verified evaluator artifact is invalid");
        }
        String implementationClass = descriptor.configuration().get("implementationClass");
        if (implementationClass == null || implementationClass.isBlank()
                || implementationClass.length() > 512 || !implementationClass.matches(
                "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            throw new IOException("evaluator descriptor must declare implementationClass");
        }

        validateJar(verifiedArtifactBytes);
        Path jar = Files.createTempFile("andalus-evaluator-", ".jar");
        try {
            Files.write(jar, verifiedArtifactBytes, StandardOpenOption.TRUNCATE_EXISTING);
            URLClassLoader loader = new EvaluatorArtifactClassLoader(
                    new URL[]{jar.toUri().toURL()}, new ApiOnlyParent(Evaluator.class.getClassLoader()));
            loaders.add(loader);
            temporaryFiles.add(jar);
            Class<?> implementation = Class.forName(implementationClass, true, loader);
            if (!Evaluator.class.isAssignableFrom(implementation)) {
                throw new IOException("artifact implementation does not implement the evaluator API");
            }
            Constructor<?> constructor = implementation.getDeclaredConstructor();
            if (!java.lang.reflect.Modifier.isPublic(constructor.getModifiers())
                    || !java.lang.reflect.Modifier.isPublic(implementation.getModifiers())) {
                throw new IOException("evaluator implementation requires a public no-argument constructor");
            }
            return (Evaluator) constructor.newInstance();
        } catch (Exception failure) {
            Files.deleteIfExists(jar);
            throw failure;
        }
    }

    private static void validateJar(byte[] bytes) throws IOException {
        Set<String> names = new HashSet<>();
        long expanded = 0;
        int entries = 0;
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(bytes))) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                Attributes attributes = manifest.getMainAttributes();
                if (attributes.getValue(Attributes.Name.CLASS_PATH) != null
                        || attributes.getValue("Premain-Class") != null
                        || attributes.getValue("Agent-Class") != null
                        || attributes.getValue("Launcher-Agent-Class") != null
                        || "true".equalsIgnoreCase(attributes.getValue("Multi-Release"))) {
                    throw new IOException("evaluator JAR manifest contains forbidden launch metadata");
                }
            }
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = jar.getNextJarEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("evaluator JAR has too many entries");
                String name = entry.getName();
                if (!safeEntryName(name) || !names.add(name) || forbiddenEntry(name)) {
                    throw new IOException("evaluator JAR contains an unsafe or duplicate entry");
                }
                long entryBytes = 0;
                int read;
                while ((read = jar.read(buffer)) != -1) {
                    entryBytes += read;
                    expanded += read;
                    if (entryBytes > MAX_ENTRY_BYTES || expanded > MAX_EXPANDED_BYTES) {
                        throw new IOException("evaluator JAR expansion limit exceeded");
                    }
                }
                long compressed = entry.getCompressedSize();
                if (compressed > 0 && entryBytes / compressed > 200) {
                    throw new IOException("evaluator JAR compression ratio is excessive");
                }
                jar.closeEntry();
            }
        } catch (IllegalArgumentException malformed) {
            throw new IOException("evaluator JAR is malformed", malformed);
        }
    }

    private static boolean safeEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("\\") || name.indexOf('\0') >= 0) return false;
        for (String segment : name.split("/")) {
            if (segment.equals("..") || segment.equals(".")) return false;
        }
        return true;
    }

    private static boolean forbiddenEntry(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".so") || lower.endsWith(".dll") || lower.endsWith(".dylib")
                || lower.endsWith(".jnilib") || lower.startsWith("meta-inf/services/")
                || lower.startsWith("meta-inf/versions/") || lower.equals("module-info.class")
                || lower.startsWith("java/") || lower.startsWith("javax/")
                || lower.startsWith("jakarta/") || lower.startsWith("tech/kayys/andalus/")
                && !lower.startsWith("tech/kayys/andalus/harness/evaluation/");
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (URLClassLoader loader : loaders) {
            try { loader.close(); } catch (IOException ex) { if (failure == null) failure = ex; }
        }
        for (Path file : temporaryFiles) {
            try { Files.deleteIfExists(file); } catch (IOException ex) { if (failure == null) failure = ex; }
        }
        loaders.clear();
        temporaryFiles.clear();
        if (failure != null) throw failure;
    }

    private static final class ApiOnlyParent extends ClassLoader {
        private ApiOnlyParent(ClassLoader apiLoader) { super(apiLoader); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("java.") || name.startsWith("jdk.")
                    || name.equals(Evaluator.class.getName())
                    || name.startsWith(Evaluator.class.getName() + "$")) {
                return super.loadClass(name, resolve);
            }
            throw new ClassNotFoundException("class is outside the evaluator API: " + name);
        }

        @Override public URL getResource(String name) { return null; }
        @Override public InputStream getResourceAsStream(String name) { return null; }
    }

    private static final class EvaluatorArtifactClassLoader extends URLClassLoader {
        private EvaluatorArtifactClassLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (name.startsWith("java.") || name.startsWith("jdk.")
                            || name.equals(Evaluator.class.getName())
                            || name.startsWith(Evaluator.class.getName() + "$")) {
                        loaded = getParent().loadClass(name);
                    } else {
                        try { loaded = findClass(name); }
                        catch (ClassNotFoundException absent) { loaded = getParent().loadClass(name); }
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override public URL getResource(String name) { return findResource(name); }
    }
}
