package tech.kayys.andalus.provider.aqli.strategy;

import tech.kayys.aqli.factory.AqliSdkFactory;
import tech.kayys.aqli.sdk.config.SdkConfig;
import tech.kayys.aqli.sdk.core.AqliSdk;
import tech.kayys.andalus.provider.ChatMessage;
import tech.kayys.andalus.provider.StreamEvent;
import tech.kayys.andalus.provider.ToolSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import tech.kayys.aqli.spi.inference.InferenceRequest;
import tech.kayys.aqli.spi.Message;

public class RestAqliStrategy implements AqliStrategy {

    private final AqliSdk sdk;

    public RestAqliStrategy(String baseUrl, String apiKey) {
        try {
            this.sdk = AqliSdkFactory.createRemoteSdk(baseUrl, apiKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize REST Aqli SDK", e);
        }
    }

    @Override
    public void streamChat(List<ChatMessage> messages, String systemPrompt, List<ToolSpec> tools, double temperature, int maxTokens, Consumer<StreamEvent> onEvent) throws IOException, InterruptedException {
        InferenceRequest.Builder requestBuilder = InferenceRequest.builder()
                .model("default") // In real usage, this should come from provider config
                .temperature(temperature)
                .maxTokens(maxTokens);

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            requestBuilder.message(new Message(Message.Role.SYSTEM, systemPrompt));
        }

        for (ChatMessage msg : messages) {
            Message.Role role = msg.role == ChatMessage.Role.ASSISTANT ? Message.Role.ASSISTANT : Message.Role.USER;
            requestBuilder.message(new Message(role, msg.textOnly()));
        }
        
        CountDownLatch latch = new CountDownLatch(1);
        
        sdk.streamCompletion(requestBuilder.build())
           .subscribe().with(
               chunk -> {
                   if (chunk.finished()) {
                       onEvent.accept(new StreamEvent.MessageStop(chunk.finishReason() != null ? chunk.finishReason() : "stop"));
                   } else if (chunk.delta() != null && !chunk.delta().isEmpty()) {
                       onEvent.accept(new StreamEvent.TextDelta(chunk.delta()));
                   }
               },
               failure -> {
                   onEvent.accept(new StreamEvent.Error(failure.getMessage()));
                   latch.countDown();
               },
               () -> latch.countDown()
           );
           
        latch.await();
    }
}
