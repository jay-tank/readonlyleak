package io.github.jaytank.readonlyleak;

import java.util.List;

/** Minimal, dependency-free JSON serialization for the findings report. */
final class JsonWriter {

    private JsonWriter() {
    }

    static String write(List<Finding> findings, int filesScanned) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"filesScanned\": ").append(filesScanned).append(",\n");
        sb.append("  \"findings\": [\n");
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            sb.append("    {\n");
            sb.append("      \"ruleId\": ").append(quote(f.ruleId())).append(",\n");
            sb.append("      \"severity\": ").append(quote(f.severity().name())).append(",\n");
            sb.append("      \"file\": ").append(quote(f.file())).append(",\n");
            sb.append("      \"line\": ").append(f.line()).append(",\n");
            sb.append("      \"location\": ").append(quote(f.location())).append(",\n");
            sb.append("      \"message\": ").append(quote(f.message())).append("\n");
            sb.append("    }").append(i == findings.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  ],\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"blockers\": ").append(findings.size()).append(",\n");
        sb.append("    \"failed\": ").append(!findings.isEmpty()).append("\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append("\"");
        return out.toString();
    }
}
