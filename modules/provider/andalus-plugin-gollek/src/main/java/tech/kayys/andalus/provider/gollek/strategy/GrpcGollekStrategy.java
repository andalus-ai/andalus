package tech.kayys.andalus.provider.aqli.strategy;

import tech.kayys.andalus.provider.ChatMessage;
import tech.kayys.andalus.provider.StreamEvent;
import tech.kayys.andalus.provider.ToolSpec;
import tech.kayys.aqli.protobuf.AqliServiceGrpc;
import tech.kayys.aqli.protobuf.ChatRequest;
import tech.kayys.aqli.protobuf.ChatResponse;
import tech.kayys.aqli.protobuf.Message;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

public class GrpcAqliStrategy implements AqliStrategy {

    private final String grpcTarget;
    private final ManagedChannel channel;
    private final AqliServiceGrpc.AqliServiceStub asyncStub;

    public GrpcAqliStrategy(String grpcTarget) {
        this.grpcTarget = grpcTarget;
        this.channel = ManagedChannelBuilder.forTarget(grpcTarget).usePlaintext().build();
        this.asyncStub = AqliServiceGrpc.newStub(channel);
    }

    @Override
    public void streamChat(List<ChatMessage> messages, String systemPrompt, List<ToolSpec> tools, double temperature, int maxTokens, Consumer<StreamEvent> onEvent) throws IOException, InterruptedException {
        ChatRequest.Builder requestBuilder = ChatRequest.newBuilder()
                .setModelId("default")
                .setTemperature((float) temperature)
                .setMaxTokens(maxTokens);

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            requestBuilder.addMessages(Message.newBuilder().setRole("system").setContent(systemPrompt).build());
        }

        for (ChatMessage msg : messages) {
            String roleStr = msg.role == ChatMessage.Role.ASSISTANT ? "assistant" : "user";
            requestBuilder.addMessages(Message.newBuilder().setRole(roleStr).setContent(msg.textOnly()).build());
        }
        
        CountDownLatch latch = new CountDownLatch(1);
        
        asyncStub.streamChat(requestBuilder.build(), new StreamObserver<ChatResponse>() {
            @Override
            public void onNext(ChatResponse value) {
                if (value.getMessage() != null && value.getMessage().getContent() != null && !value.getMessage().getContent().isEmpty()) {
                    onEvent.accept(new StreamEvent.TextDelta(value.getMessage().getContent()));
                }
                if (value.getFinishReason() != null && !value.getFinishReason().isEmpty()) {
                    onEvent.accept(new StreamEvent.MessageStop(value.getFinishReason()));
                }
            }

            @Override
            public void onError(Throwable t) {
                onEvent.accept(new StreamEvent.Error(t.getMessage()));
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        latch.await();
    }
}
