package io.github.jaytank.readonlyleak;

/**
 * A single detected issue: a Spring {@code @Transactional(readOnly = true)}
 * method (direct or inherited from a class-level annotation) whose body
 * textually calls a mutating persistence operation ({@code RL001}).
 */
public record Finding(
        String ruleId,
        Severity severity,
        String file,
        int line,
        String location,
        String message
) {
    public enum Severity {
        BLOCKER
    }
}
