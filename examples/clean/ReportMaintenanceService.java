package com.example.reports;

import org.springframework.transaction.annotation.Transactional;

/**
 * Class-level readOnly = true, but archive() below has its own
 * method-level @Transactional with no readOnly attribute. Spring's real
 * precedence rule is that a method-level annotation REPLACES the
 * class-level one entirely for that method -- so archive() actually runs
 * with readOnly = false, not readOnly = true, and its save() call is
 * correctly not a violation of any readOnly claim. Easy to misjudge as a
 * bug if you assume readOnly = true "inherits down" onto an overriding
 * annotation instead of being fully replaced by it -- see DETAILS.md.
 */
@Transactional(readOnly = true)
public class ReportMaintenanceService {

    private final ReportRepository reportRepository;

    public ReportMaintenanceService(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    public Report lookup(Long id) {
        return reportRepository.findById(id).orElseThrow();
    }

    @Transactional
    public Report archive(Report report) {
        report.setArchived(true);
        return reportRepository.save(report);
    }
}
