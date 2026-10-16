package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AndalusKnowledgeCommandsTest {

    @Test
    public void testKnowledgeQueryCommand() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(null, new PrintStream(out), new PrintStream(err), "knowledge", "query", "authentication requirements");
        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("KNOWLEDGE EVIDENCE SEARCH"));
    }

    @Test
    public void testKnowledgeResolveCommand() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(null, new PrintStream(out), new PrintStream(err), "knowledge", "resolve", "How to configure mTLS");
        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("KNOWLEDGE ANSWER CONSENSUS RESOLUTION"));
    }

    @Test
    public void testKnowledgeVerifyCommand() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(null, new PrintStream(out), new PrintStream(err), "knowledge", "verify", "art-12345");
        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("KNOWLEDGE ARTIFACT VERIFICATION"));
    }

    @Test
    public void testKnowledgeConsensusCommand() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(null, new PrintStream(out), new PrintStream(err), "knowledge", "consensus", "prop-999");
        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("KNOWLEDGE CONSENSUS DETAILS"));
    }

    @Test
    public void testKnowledgeSyncCommand() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(null, new PrintStream(out), new PrintStream(err), "knowledge", "sync");
        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("KNOWLEDGE INVENTORY RECONCILIATION"));
    }
}
