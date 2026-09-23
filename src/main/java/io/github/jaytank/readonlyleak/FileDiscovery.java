package io.github.jaytank.readonlyleak;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Discovers {@code .java} files under the given paths. */
public final class FileDiscovery {

    private FileDiscovery() {
    }

    public static List<Path> scan(List<Path> roots) throws IOException {
        List<Path> java = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".java"))
                            .forEach(java::add);
                }
            } else if (Files.isRegularFile(root) && root.toString().endsWith(".java")) {
                java.add(root);
            }
        }
        return java;
    }
}
