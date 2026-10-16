import sys
import re

with open('src/main/java/tech/kayys/andalus/provider/aqli/AqliInferenceProvider.java', 'r') as f:
    content = f.read()

# Replace the stream method definition with streamCli
content = content.replace('public CompletionStream stream(CompletionRequest request) throws Exception {', 'private CompletionStream streamCli(CompletionRequest request) throws Exception {')

# Inject the new stream and streamGrpc methods right above streamCli
injection = """
    @Override
    public CompletionStream stream(CompletionRequest request) throws Exception {
        String grpcTarget = System.getenv("AQLI_GRPC_TARGET");
        if (grpcTarget == null || grpcTarget.isBlank()) {
            grpcTarget = "localhost:9131"; // Use gRPC by default!
        }
        
        try {
            return streamGrpc(request, grpcTarget);
        } catch (Exception e) {
            System.err.println("[Aqli] gRPC failed, falling back to CLI. Error: " + e.getMessage());
            return streamCli(request);
        }
    }

    private CompletionStream streamGrpc(CompletionRequest request, String target) {
        String model = request.model() != null && !request.model().isBlank()
                ? request.model() : "default";
        int maxTokens = request.maxTokens() > 0 ? request.maxTokens() : 2048;

        tech.kayys.aqli.protobuf.ChatRequest.Builder reqBuilder = tech.kayys.aqli.protobuf.ChatRequest.newBuilder()
                .setModelId(model)
                .setMaxTokens(maxTokens)
                .setTemperature((float) (request.temperature() != null ? request.temperature() : 0.7));

        if (request.messages() != null) {
            for (Message m : request.messages()) {
                String roleStr = m.role() != null ? m.role().name().toLowerCase() : "user";
                if (roleStr.equals("system") && (m.content() == null || m.content().isBlank())) {
                    continue;
                }
                reqBuilder.addMessages(tech.kayys.aqli.protobuf.Message.newBuilder().setRole(roleStr).setContent(m.content() != null ? m.content() : "").build());
            }
        }

        io.grpc.ManagedChannel channel = io.grpc.ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        tech.kayys.aqli.protobuf.AqliServiceGrpc.AqliServiceBlockingStub stub = tech.kayys.aqli.protobuf.AqliServiceGrpc.newBlockingStub(channel);
        
        // This will block until the connection is established or fails
        java.util.Iterator<tech.kayys.aqli.protobuf.ChatResponse> iter = stub.streamChat(reqBuilder.build());
        final String streamId = "g-" + UUID.randomUUID();

        return new CompletionStream() {
            private tech.kayys.aqli.protobuf.ChatResponse nextVal = null;
            private boolean done = false;

            private void fetch() {
                if (nextVal != null || done) return;
                try {
                    if (iter.hasNext()) {
                        nextVal = iter.next();
                    } else {
                        done = true;
                    }
                } catch (Exception e) {
                    done = true;
                }
            }

            @Override public boolean hasNext() {
                fetch();
                return !done;
            }

            @Override public CompletionResult next() {
                fetch();
                if (done || nextVal == null) return CompletionResult.of("");
                String content = nextVal.getMessage() != null ? nextVal.getMessage().getContent() : "";
                nextVal = null;
                return CompletionResult.of(content);
            }

            @Override public void close() {
                done = true;
                if (channel != null) {
                    channel.shutdownNow();
                }
            }

            @Override public boolean isComplete() { return done; }
            @Override public String getStreamId() { return streamId; }
        };
    }

    private CompletionStream streamCli(CompletionRequest request) throws Exception {
"""

content = content.replace('private CompletionStream streamCli(CompletionRequest request) throws Exception {', injection)

with open('src/main/java/tech/kayys/andalus/provider/aqli/AqliInferenceProvider.java', 'w') as f:
    f.write(content)
