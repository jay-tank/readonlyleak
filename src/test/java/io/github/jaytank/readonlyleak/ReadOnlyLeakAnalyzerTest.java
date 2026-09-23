package io.github.jaytank.readonlyleak;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyLeakAnalyzerTest {

    private List<Finding> analyze(String source) {
        CompilationUnit cu = StaticJavaParser.parse(source);
        return new ReadOnlyLeakAnalyzer().analyze(cu, "Test.java");
    }

    private Set<String> ruleIds(List<Finding> findings) {
        return findings.stream().map(Finding::ruleId).collect(Collectors.toSet());
    }

    // --- Positive cases (must be flagged) ---

    @Test
    void methodLevelReadOnlyTrueCallingRepositorySaveIsFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class UserService {
                    @Transactional(readOnly = true)
                    void touchLastSeen(User user) {
                        userRepository.save(user);
                    }
                }
                """;
        List<Finding> findings = analyze(src);
        assertEquals(Set.of("RL001"), ruleIds(findings));
    }

    @Test
    void methodLevelReadOnlyTrueCallingEntityManagerPersistIsFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class AuditService {
                    @Transactional(readOnly = true)
                    void recordAccess(AuditEntry entry) {
                        entityManager.persist(entry);
                    }
                }
                """;
        List<Finding> findings = analyze(src);
        assertEquals(Set.of("RL001"), ruleIds(findings));
    }

    @Test
    void methodLevelReadOnlyTrueCallingSaveAndFlushIsFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    void bump(Order order) {
                        orderRepository.saveAndFlush(order);
                    }
                }
                """;
        assertEquals(Set.of("RL001"), ruleIds(analyze(src)));
    }

    @Test
    void methodLevelReadOnlyTrueCallingDeleteIsFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    void purge(Order order) {
                        orderRepository.delete(order);
                    }
                }
                """;
        assertEquals(Set.of("RL001"), ruleIds(analyze(src)));
    }

    @Test
    void methodLevelReadOnlyTrueCallingDeleteByIdIsFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    void purge(Long id) {
                        orderRepository.deleteById(id);
                    }
                }
                """;
        assertEquals(Set.of("RL001"), ruleIds(analyze(src)));
    }

    @Test
    void methodLevelReadOnlyTrueCallingDeleteAllIsFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    void purgeAll() {
                        orderRepository.deleteAll();
                    }
                }
                """;
        assertEquals(Set.of("RL001"), ruleIds(analyze(src)));
    }

    @Test
    void methodLevelReadOnlyTrueCallingEntityManagerMergeIsFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    void reconcile(Order order) {
                        entityManager.merge(order);
                    }
                }
                """;
        assertEquals(Set.of("RL001"), ruleIds(analyze(src)));
    }

    @Test
    void methodLevelReadOnlyTrueCallingEntityManagerRemoveIsFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    void wipe(Order order) {
                        entityManager.remove(order);
                    }
                }
                """;
        assertEquals(Set.of("RL001"), ruleIds(analyze(src)));
    }

    @Test
    void methodLevelReadOnlyTrueCallingExecuteUpdateIsFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    void bulkArchive() {
                        Query query = entityManager.createQuery("update Order o set o.archived = true");
                        query.executeUpdate();
                    }
                }
                """;
        assertEquals(Set.of("RL001"), ruleIds(analyze(src)));
    }

    @Test
    void classLevelReadOnlyTrueWithNoMethodOverrideCallingDeleteIsFlaggedViaInheritance() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                @Transactional(readOnly = true)
                class OrderService {
                    void purge(Order order) {
                        orderRepository.delete(order);
                    }
                }
                """;
        assertEquals(Set.of("RL001"), ruleIds(analyze(src)));
    }

    @Test
    void classLevelReadOnlyTrueWithNoMethodOverrideCallingSaveIsFlaggedViaInheritance() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                @Transactional(readOnly = true)
                class OrderService {
                    void touch(Order order) {
                        orderRepository.save(order);
                    }
                }
                """;
        assertEquals(Set.of("RL001"), ruleIds(analyze(src)));
    }

    @Test
    void methodLevelReadOnlyTrueWithOtherAttributesStillFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRED, readOnly = true)
                    void touch(Order order) {
                        orderRepository.save(order);
                    }
                }
                """;
        assertEquals(Set.of("RL001"), ruleIds(analyze(src)));
    }

    @Test
    void mutatingCallNestedInsideIfBlockIsStillFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    void maybeTouch(Order order, boolean flag) {
                        if (flag) {
                            orderRepository.save(order);
                        }
                    }
                }
                """;
        assertEquals(Set.of("RL001"), ruleIds(analyze(src)));
    }

    @Test
    void twoMutatingCallsInSameMethodProduceTwoFindings() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    void sync(Order order, Invoice invoice) {
                        orderRepository.save(order);
                        invoiceRepository.save(invoice);
                    }
                }
                """;
        assertEquals(2, analyze(src).size());
    }

    // --- Negative cases (must NOT be flagged) ---

    @Test
    void plainTransactionalWithNoReadOnlyCallingSaveIsNotFlagged() {
        // No readOnly claim being made at all -- mutations are the normal,
        // expected case here.
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional
                    void place(Order order) {
                        orderRepository.save(order);
                    }
                }
                """;
        assertTrue(analyze(src).isEmpty());
    }

    @Test
    void explicitReadOnlyFalseCallingSaveIsNotFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = false)
                    void place(Order order) {
                        orderRepository.save(order);
                    }
                }
                """;
        assertTrue(analyze(src).isEmpty());
    }

    @Test
    void readOnlyTrueMethodWithOnlyReadsIsNotFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    Order lookup(Long id) {
                        return orderRepository.findById(id).orElseThrow();
                    }
                }
                """;
        assertTrue(analyze(src).isEmpty());
    }

    @Test
    void readOnlyTrueMethodCallingFindAllIsNotFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    java.util.List<Order> listAll() {
                        return orderRepository.findAll();
                    }
                }
                """;
        assertTrue(analyze(src).isEmpty());
    }

    @Test
    void nonTransactionalMethodCallingSaveIsNotFlagged() {
        // Out of scope: no readOnly claim being violated since there's no
        // @Transactional at all.
        String src = """
                class OrderService {
                    void place(Order order) {
                        orderRepository.save(order);
                    }
                }
                """;
        assertTrue(analyze(src).isEmpty());
    }

    @Test
    void classLevelReadOnlyTrueButMethodLevelOverrideWithNoReadOnlyResetsToFalseNotFlagged() {
        // The trickiest case: Spring's real precedence is that a
        // method-level @Transactional REPLACES the class-level one
        // entirely for that method -- including readOnly defaulting back
        // to false, even though the class-level annotation says
        // readOnly = true. Must NOT be flagged.
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                @Transactional(readOnly = true)
                class OrderService {
                    @Transactional
                    void place(Order order) {
                        orderRepository.save(order);
                    }
                }
                """;
        assertTrue(analyze(src).isEmpty());
    }

    @Test
    void classLevelReadOnlyTrueButMethodLevelExplicitFalseNotFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                @Transactional(readOnly = true)
                class OrderService {
                    @Transactional(readOnly = false)
                    void place(Order order) {
                        orderRepository.save(order);
                    }
                }
                """;
        assertTrue(analyze(src).isEmpty());
    }

    @Test
    void classLevelReadOnlyTrueOtherMethodInSameClassWithOwnFalseOverrideNotFlaggedWhileSiblingIsFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                @Transactional(readOnly = true)
                class OrderService {
                    @Transactional
                    void place(Order order) {
                        orderRepository.save(order);
                    }
                    void purge(Order order) {
                        orderRepository.delete(order);
                    }
                }
                """;
        List<Finding> findings = analyze(src);
        assertEquals(1, findings.size());
        assertEquals("purge", findings.get(0).location());
    }

    @Test
    void readOnlyTrueMethodWithEmptyBodyIsNotFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    void noOp() {
                    }
                }
                """;
        assertTrue(analyze(src).isEmpty());
    }

    @Test
    void readOnlyTrueMethodCallingHelperThatMutatesElsewhereIsNotFlagged() {
        // The mutating call is not textually inside this method -- it's in
        // a helper method this one calls. This tool is a textual/structural
        // check per method body, not interprocedural, so this is out of
        // scope by design.
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                class OrderService {
                    @Transactional(readOnly = true)
                    void process(Order order) {
                        helper(order);
                    }
                    void helper(Order order) {
                        orderRepository.save(order);
                    }
                }
                """;
        assertTrue(analyze(src).isEmpty());
    }

    @Test
    void abstractReadOnlyTrueMethodWithNoBodyIsNotFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                interface OrderService {
                    @Transactional(readOnly = true)
                    void archive(Order order);
                }
                """;
        assertTrue(analyze(src).isEmpty());
    }

    @Test
    void classLevelMarkerAnnotationWithNoReadOnlyCallingSaveIsNotFlagged() {
        String src = """
                import org.springframework.transaction.annotation.Transactional;
                @Transactional
                class OrderService {
                    void place(Order order) {
                        orderRepository.save(order);
                    }
                }
                """;
        assertTrue(analyze(src).isEmpty());
    }
}
