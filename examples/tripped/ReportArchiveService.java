package com.example.reports;

import org.springframework.transaction.annotation.Transactional;

/**
 * Class-level readOnly = true, intended for the reporting queries this
 * service mostly does -- but recordAccess() has no method-level
 * @Transactional override, so it inherits readOnly = true from the class
 * and still ends up calling EntityManager.persist(), a mutating call.
 */
@Transactional(readOnly = true)
public class ReportArchiveService {

    private final jakarta.persistence.EntityManager entityManager;

    public ReportArchiveService(jakarta.persistence.EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Report lookup(Long id) {
        return entityManager.find(Report.class, id);
    }

    public void recordAccess(AccessLogEntry entry) {
        entityManager.persist(entry);
    }
}
