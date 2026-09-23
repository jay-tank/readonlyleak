package io.github.jaytank.readonlyleak;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point.
 *
 * <pre>
 *   readonlyleak [options] &lt;path...&gt;
 *
 *   --json   emit machine-readable JSON instead of text
 *   --help   print usage and exit 0
 * </pre>
 *
 * Exit codes: 0 = clean, 1 = at least one finding (RL001), 2 = usage/IO error.
 */
public final class Main {

    static {
        StaticJavaParser.getConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    private Main() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        boolean json = false;
        List<String> paths = new ArrayList<>();

        for (String arg : args) {
            switch (arg) {
                case "--json" -> json = true;
                case "--help", "-h" -> {
                    printUsage(out);
                    return 0;
                }
                default -> paths.add(arg);
            }
        }

        if (paths.isEmpty()) {
            err.println("readonlyleak: no paths given");
            printUsage(err);
            return 2;
        }

        List<Path> roots = paths.stream().map(Path::of).toList();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                err.println("readonlyleak: path not found: " + root);
                return 2;
            }
        }

        List<Path> javaFiles;
        try {
            javaFiles = FileDiscovery.scan(roots);
        } catch (IOException e) {
            err.println("readonlyleak: failed to scan paths: " + e.getMessage());
            return 2;
        }

        List<Finding> findings = new ArrayList<>();
        ReadOnlyLeakAnalyzer analyzer = new ReadOnlyLeakAnalyzer();
        int filesScanned = 0;
        for (Path javaFile : javaFiles) {
            filesScanned++;
            CompilationUnit cu;
            try {
                cu = StaticJavaParser.parse(javaFile);
            } catch (Exception e) {
                err.println("readonlyleak: skipping unparsable file " + javaFile + ": " + e.getMessage());
                continue;
            }
            findings.addAll(analyzer.analyze(cu, javaFile.toString()));
        }

        findings.sort((a, b) -> {
            int byFile = a.file().compareTo(b.file());
            if (byFile != 0) {
                return byFile;
            }
            return Integer.compare(a.line(), b.line());
        });

        if (json) {
            out.println(JsonWriter.write(findings, filesScanned));
        } else {
            printText(out, findings, filesScanned);
        }

        return findings.isEmpty() ? 0 : 1;
    }

    private static void printText(PrintStream out, List<Finding> findings, int filesScanned) {
        for (Finding f : findings) {
            out.println(f.file() + ":" + f.line() + ": [" + f.severity() + "] " + f.ruleId() + " " + f.message());
        }
        out.println();
        out.println("readonlyleak: scanned " + filesScanned + " file(s), " + findings.size()
                + " finding(s) (RL001)");
    }

    private static void printUsage(PrintStream out) {
        out.println("""
                Usage: readonlyleak [options] <path...>

                Scans Java/Spring source for @Transactional(readOnly = true)
                methods (method-level, or inherited from a class-level
                annotation when the method has no override of its own) whose
                body calls a mutating persistence operation -- a Spring Data
                JPA repository's .save(...)/.saveAndFlush(...)/.delete(...)/
                .deleteById(...)/.deleteAll(...), a JPA EntityManager's
                .persist(...)/.merge(...)/.remove(...), or a Query/TypedQuery's
                .executeUpdate(). readOnly = true is documented by Spring as an
                optimization hint, not an enforced guarantee -- the mutation
                can silently commit anyway, silently fail to flush, or throw
                far from the actual call site, depending on JPA provider/
                driver/connection pool.

                Options:
                  --json   emit machine-readable JSON instead of text
                  --help   show this message

                Exit codes: 0 clean, 1 findings present, 2 usage/IO error.
                """);
    }
}
