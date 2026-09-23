package io.github.jaytank.readonlyleak;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds Spring {@code @Transactional(readOnly = true)} methods whose body
 * textually calls a mutating persistence operation.
 *
 * <p>{@code readOnly = true} is documented by Spring itself as a HINT to the
 * underlying transaction manager / JPA provider / DB driver, primarily used
 * for optimizations (skipping dirty-checking, routing to a read replica) --
 * it is <em>not</em> an enforced guarantee that mutating calls inside the
 * transaction will be rejected. Depending on the exact combination of JPA
 * provider, driver, and connection pool settings, a mutation inside a
 * {@code readOnly = true} transaction can silently succeed and commit
 * anyway, silently succeed and then silently fail to actually flush, or
 * throw at flush/commit time far from the call that caused it. None of
 * those outcomes match what a developer meant by marking a method
 * {@code readOnly = true} in the first place. See DETAILS.md for the full
 * mechanics and the exact method-vs-class precedence rule this analyzer
 * implements.
 *
 * <p>AST-based (JavaParser), matching the rest of this tool series -- never
 * compiles or executes scanned code. Mutating calls are matched by simple
 * method name, not resolved symbols/receiver types (no compile classpath is
 * available at scan time); see DETAILS.md for what that trade-off means.
 */
public final class ReadOnlyLeakAnalyzer {

    private static final String TRANSACTIONAL_ANNOTATION = "Transactional";
    private static final String READ_ONLY_ATTRIBUTE = "readOnly";

    private static final Set<String> MUTATING_CALL_NAMES = Set.of(
            "save",
            "saveAndFlush",
            "delete",
            "deleteById",
            "deleteAll",
            "persist",
            "merge",
            "remove",
            "executeUpdate"
    );

    public List<Finding> analyze(CompilationUnit cu, String fileName) {
        List<Finding> findings = new ArrayList<>();

        for (ClassOrInterfaceDeclaration classDecl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            Optional<AnnotationExpr> classAnnotation = classDecl.getAnnotationByName(TRANSACTIONAL_ANNOTATION);

            for (MethodDeclaration method : classDecl.getMethods()) {
                if (method.getBody().isEmpty()) {
                    // Abstract/interface declaration -- no body to inspect.
                    continue;
                }

                Optional<AnnotationExpr> methodAnnotation = method.getAnnotationByName(TRANSACTIONAL_ANNOTATION);
                Boolean effectiveReadOnly = resolveEffectiveReadOnly(methodAnnotation, classAnnotation);

                if (effectiveReadOnly == null || !effectiveReadOnly) {
                    // Either no @Transactional at all (method-level nor
                    // inherited from the class), or it resolves to
                    // readOnly = false -- mutations are the normal,
                    // expected case there, out of scope for this rule.
                    continue;
                }

                for (MethodCallExpr call : method.getBody().get().findAll(MethodCallExpr.class)) {
                    if (!MUTATING_CALL_NAMES.contains(call.getNameAsString())) {
                        continue;
                    }
                    int line = call.getBegin().map(p -> p.line).orElse(-1);
                    findings.add(new Finding(
                            "RL001",
                            Finding.Severity.BLOCKER,
                            fileName,
                            line,
                            method.getNameAsString(),
                            "@Transactional(readOnly = true) method '" + method.getNameAsString()
                                    + "' calls mutating persistence operation '" + call.getNameAsString() + "(...)' -- "
                                    + "readOnly = true is a HINT to the JPA provider/transaction manager, not an"
                                    + " enforced guarantee: depending on the provider, driver, and connection pool,"
                                    + " this mutation can silently commit anyway, silently fail to flush, or throw"
                                    + " far from this call site."
                    ));
                }
            }
        }

        return findings;
    }

    /**
     * Resolves whether a method's effective {@code @Transactional} setting
     * is {@code readOnly = true}, applying Spring's real precedence rule:
     * a method-level {@code @Transactional} annotation, if present, always
     * <em>replaces</em> the class-level one entirely for that method --
     * including its {@code readOnly} attribute defaulting back to
     * {@code false} when the method-level annotation doesn't set it, even
     * if the class-level annotation says {@code readOnly = true}. The
     * class-level annotation is only consulted at all when the method has
     * no {@code @Transactional} annotation of its own.
     *
     * @return {@code true}/{@code false} for the resolved readOnly value,
     *         or {@code null} if the method isn't transactional at all
     *         (no method-level annotation and no class-level annotation).
     */
    private static Boolean resolveEffectiveReadOnly(Optional<AnnotationExpr> methodAnnotation,
                                                      Optional<AnnotationExpr> classAnnotation) {
        if (methodAnnotation.isPresent()) {
            return readOnlyValue(methodAnnotation.get());
        }
        if (classAnnotation.isPresent()) {
            return readOnlyValue(classAnnotation.get());
        }
        return null;
    }

    /** Extracts the {@code readOnly} attribute's boolean value, defaulting to {@code false} when unset. */
    private static boolean readOnlyValue(AnnotationExpr annotation) {
        if (!annotation.isNormalAnnotationExpr()) {
            // @Transactional (marker, no attributes) or
            // @Transactional(someSingleValue) -- readOnly not settable in
            // either shape, so it's at its default: false.
            return false;
        }
        NormalAnnotationExpr normal = annotation.asNormalAnnotationExpr();
        for (MemberValuePair pair : normal.getPairs()) {
            if (READ_ONLY_ATTRIBUTE.equals(pair.getNameAsString())) {
                Expression value = pair.getValue();
                if (value.isBooleanLiteralExpr()) {
                    return value.asBooleanLiteralExpr().getValue();
                }
                // Non-literal (e.g. a constant reference) -- can't resolve
                // without symbol resolution; treat as not-provably-true
                // rather than risk a false positive.
                return false;
            }
        }
        return false;
    }
}
