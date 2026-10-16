package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.client.Andalus;
import tech.kayys.andalus.client.AndalusClient;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusCliContextTest {

    @Test
    void contextDoesNotResolveClientUntilClientIsRequested() {
        AtomicInteger calls = new AtomicInteger();

        AndalusCliContext context = new AndalusCliContext(
                () -> {
                    calls.incrementAndGet();
                    return Andalus.client();
                },
                ByteArrayInputStream.nullInputStream(),
                stream(new ByteArrayOutputStream()),
                stream(new ByteArrayOutputStream()));

        assertThat(calls).hasValue(0);
        assertThat(context.in()).isNotNull();
        assertThat(calls).hasValue(0);
    }

    @Test
    void contextResolvesClientLazilyAndCachesIt() {
        AndalusAqliSdk sdk = AndalusAqliSdk.local();
        AndalusClient client = Andalus.client(sdk);
        AtomicInteger calls = new AtomicInteger();

        AndalusCliContext context = new AndalusCliContext(
                () -> {
                    calls.incrementAndGet();
                    return client;
                },
                ByteArrayInputStream.nullInputStream(),
                stream(new ByteArrayOutputStream()),
                stream(new ByteArrayOutputStream()));

        assertThat(calls).hasValue(0);
        assertThat(context.client()).isSameAs(client);
        assertThat(context.client()).isSameAs(context.client());
        assertThat(calls).hasValue(1);
    }

    @Test
    void contextCarriesCliStreams() {
        ByteArrayInputStream in = new ByteArrayInputStream("prompt".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        AndalusCliContext context = new AndalusCliContext(
                Andalus::client,
                in,
                stream(out),
                stream(err));

        assertThat(context.in()).isSameAs(in);
        assertThat(context.out()).isNotNull();
        assertThat(context.err()).isNotNull();
    }

    @Test
    void contextRendersStandardCommandFailure() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        AndalusCliContext context = new AndalusCliContext(
                Andalus::client,
                ByteArrayInputStream.nullInputStream(),
                stream(new ByteArrayOutputStream()),
                stream(err));

        int exitCode = context.commandFailure(new IllegalArgumentException("bad input"));

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .isEqualTo("Andalus command failed: bad input" + System.lineSeparator());
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }
}
