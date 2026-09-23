package io.github.jaytank.readonlyleak;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainCliIntegrationTest {

    @Test
    void trippedExampleExitsNonZeroWithFindings() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"examples/tripped"}, new PrintStream(out), new PrintStream(err));
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, text);
        assertTrue(text.contains("RL001"), text);
        assertTrue(text.contains("touchLastSeen"), text);
        assertTrue(text.contains("recordAccess"), text);
    }

    @Test
    void cleanExampleExitsZero() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"examples/clean"}, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonOutputContainsRule() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Main.run(new String[] {"--json", "examples/tripped"}, new PrintStream(out), new PrintStream(err));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"ruleId\": \"RL001\""), text);
    }

    @Test
    void jsonOutputCleanForCleanExample() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Main.run(new String[] {"--json", "examples/clean"}, new PrintStream(out), new PrintStream(err));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"findings\": [\n  ]") || text.contains("\"blockers\": 0"), text);
    }

    @Test
    void noPathsGivenIsUsageError() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {}, new PrintStream(out), new PrintStream(err));
        assertEquals(2, code);
    }

    @Test
    void nonexistentPathIsUsageError() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"does/not/exist"}, new PrintStream(out), new PrintStream(err));
        assertEquals(2, code);
    }

    @Test
    void helpFlagPrintsUsageAndExitsZero() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"--help"}, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage: readonlyleak"));
    }
}
