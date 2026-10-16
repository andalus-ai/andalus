package tech.kayys.andalus.api.grpc;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC service bridging Andalus "andalus code" functionality to remote clients.
 *
 * Implementation notes:
 * - StreamCode should create or resume a session (via andalus/sdk bridge) and
 *   stream CodeChunk messages as the agent produces tokens/chunks.
 * - Generate is a convenience single-shot wrapper.
 *
 * The concrete integration points (Session manager, ChatSession creation,
 * transcript persistence) live in andalus/sdk/andalus-aqli-sdk and must be
 * invoked here. This class is a minimal skeleton.
 */
public class AndalusCodeService extends CodeServiceGrpc.CodeServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(AndalusCodeService.class);
    private final tech.kayys.andalus.project.ProjectStore store;

    public AndalusCodeService() {
        super();
        tech.kayys.andalus.project.ProjectStore tmp = null;
        try {
            tmp = new tech.kayys.andalus.project.ProjectStore(null);
        } catch (Exception e) {
            log.error("Failed to initialize ProjectStore: {}", e.getMessage());
        }
        this.store = tmp;
    }

    @Override
    public void streamCode(CodeRequest req, StreamObserver<CodeChunk> out) {
        log.info("gRPC StreamCode project={} session={} prompt={} once={}",
                req.getProjectId(), req.getSessionId(), req.getPrompt(), req.getOnce());

        try {
            String projectId = req.getProjectId();
            if (projectId == null || projectId.isBlank()) {
                try { projectId = store.currentProject(); } catch (Exception ignored) {}
                if (projectId == null || projectId.isBlank()) {
                    // if still missing, pick first project or create a default one
                    var list = store.listProjects();
                    if (!list.isEmpty()) projectId = list.get(0).id();
                    else {
                        tech.kayys.andalus.project.Project p = store.createProject("default", "default", System.getProperty("user.dir"));
                        projectId = p.id();
                    }
                }
            }

            String sessionId = req.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                tech.kayys.andalus.project.Session s = store.createSession(projectId, "session");
                sessionId = s.id();
            }

            // Load existing transcript and append the user message
            java.util.List<?> transcript = store.loadTranscript(projectId, sessionId);
            java.util.Map<String,Object> userMsg = new java.util.LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("text", req.getPrompt());
            userMsg.put("ts", System.currentTimeMillis());
            java.util.List<Object> newTranscript = new java.util.ArrayList<>();
            if (transcript != null) newTranscript.addAll((java.util.List) transcript);
            newTranscript.add(userMsg);
            // create final locals for inner-class capture
            final java.util.List<Object> capturedTranscript = newTranscript;
            final String pid = projectId;
            final String sid = sessionId;
            store.saveTranscript(pid, sid, (java.util.List) capturedTranscript);

            // Attempt to create an in-process Aqli ChatSession via reflection
            Object chatSession = null;
            try {
                try {
                    Class<?> factory = Class.forName("tech.kayys.aqli.factory.AqliSdkFactory");
                    java.lang.reflect.Method create = factory.getMethod("createLocalSdk");
                    Object sdk = create.invoke(null);
                    if (sdk != null) {
                        try {
                            Class<?> impl = Class.forName("tech.kayys.aqli.sdk.session.ChatSessionImpl");
                            java.lang.reflect.Constructor<?> ctor = null;
                            for (java.lang.reflect.Constructor<?> c : impl.getConstructors()) {
                                Class<?>[] pts = c.getParameterTypes();
                                if (pts.length >= 3 && pts[1] == String.class) { ctor = c; break; }
                            }
                            if (ctor != null) {
                                chatSession = ctor.newInstance(sdk, req.getPrompt() == null ? "" : req.getPrompt(), "", Boolean.TRUE);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (ClassNotFoundException cnf) {
                    // SDK unavailable
                }
            } catch (Throwable ignored) {}

            final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger(1);
            final StringBuilder acc = new StringBuilder();

            if (chatSession != null) {
                try {
                    // subscribe to stream: stream(prompt).subscribe().with(onNext, onError, onComplete)
                    Object stream = chatSession.getClass().getMethod("stream", String.class).invoke(chatSession, req.getPrompt());
                    if (stream != null) {
                        Object subscriber = stream.getClass().getMethod("subscribe").invoke(stream);
                        if (subscriber != null) {
                            java.util.function.Consumer<Object> onNext = new java.util.function.Consumer<>() {
                                @Override
                                public void accept(Object chunk) {
                                    try {
                                        String delta = null;
                                        try {
                                            delta = (String) chunk.getClass().getMethod("getDelta").invoke(chunk);
                                        } catch (Throwable t) {
                                            // fallback to toString
                                            delta = String.valueOf(chunk);
                                        }
                                        if (delta == null) delta = "";
                                        acc.append(delta);
                                        CodeChunk c = CodeChunk.newBuilder()
                                                .setSessionId(sid)
                                                .setRole("assistant")
                                                .setText(acc.toString())
                                                .setSeq(seq.getAndIncrement())
                                                .setDone(false)
                                                .build();
                                        out.onNext(c);
                                    } catch (Throwable t) {
                                        // ignore per-chunk errors
                                    }
                                }
                            };
                            java.util.function.Consumer<Throwable> onErr = new java.util.function.Consumer<>() {
                                @Override
                                public void accept(Throwable t) { out.onError(io.grpc.Status.INTERNAL.withDescription(t.getMessage()).asRuntimeException()); }
                            };
                            Runnable onComplete = new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        CodeChunk last = CodeChunk.newBuilder()
                                                .setSessionId(sid)
                                                .setRole("assistant")
                                                .setText(acc.toString().trim())
                                                .setSeq(seq.get())
                                                .setDone(true)
                                                .build();
                                        // persist assistant message
                                        java.util.Map<String,Object> assistantMsg = new java.util.LinkedHashMap<>();
                                        assistantMsg.put("role", "assistant");
                                        assistantMsg.put("text", last.getText());
                                        assistantMsg.put("ts", System.currentTimeMillis());
                                        capturedTranscript.add(assistantMsg);
                                        store.saveTranscript(pid, sid, (java.util.List) capturedTranscript);

                                        out.onNext(last);
                                        out.onCompleted();
                                    } catch (Throwable t) {
                                        out.onError(io.grpc.Status.INTERNAL.withDescription(t.getMessage()).asRuntimeException());
                                    }
                                }
                            };

                            // invoke with(Consumer, Consumer, Runnable)
                            try {
                                subscriber.getClass().getMethod("with", java.util.function.Consumer.class, java.util.function.Consumer.class, Runnable.class)
                                        .invoke(subscriber, onNext, onErr, onComplete);
                            } catch (NoSuchMethodException nsme) {
                                try {
                                    subscriber.getClass().getMethod("with", java.util.function.Consumer.class, java.util.function.Consumer.class, java.lang.Runnable.class)
                                            .invoke(subscriber, onNext, onErr, onComplete);
                                } catch (Throwable t) {
                                    // unsupported subscribe shape; fallback to echo
                                    throw t;
                                }
                            }

                            return; // streaming will call onComplete later
                        }
                    }
                } catch (Throwable t) {
                    // fall back to echo below
                }
            }

            // Fallback to simulated echo if SDK streaming unavailable
            String prompt = req.getPrompt() == null ? "" : req.getPrompt();
            String reply = "Echo: " + prompt;
            String[] parts = reply.split("\\s+");
            int seqInt = 1;
            for (String w : parts) {
                acc.append(w).append(" ");
                CodeChunk c = CodeChunk.newBuilder()
                        .setSessionId(sid)
                        .setRole("assistant")
                        .setText(acc.toString())
                        .setSeq(seqInt++)
                        .setDone(false)
                        .build();
                out.onNext(c);
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }
            CodeChunk last = CodeChunk.newBuilder()
                    .setSessionId(sessionId)
                    .setRole("assistant")
                    .setText(acc.toString().trim())
                    .setSeq(seqInt)
                    .setDone(true)
                    .build();
            // persist assistant message
            java.util.Map<String,Object> assistantMsg = new java.util.LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("text", last.getText());
            assistantMsg.put("ts", System.currentTimeMillis());
            capturedTranscript.add(assistantMsg);
            store.saveTranscript(pid, sid, (java.util.List) capturedTranscript);

            out.onNext(last);
            out.onCompleted();
        } catch (Exception e) {
            log.error("streamCode failed: {}", e.getMessage());
            out.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void generate(CodeRequest req, StreamObserver<CodeChunk> out) {
        // Simple wrapper: call streamCode and return the final chunk (here simplified)
        streamCode(req, out);
    }
}
