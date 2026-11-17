package com.bettercontent.spaceflight.qualification;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test-only aggregation of pending observations. No trusted evidence validator
 * exists in batch 1, so self-reported `passed`, `failed` or `not_run` rows
 * cannot establish a result. Their vocabulary is reserved for later validation.
 */
final class QualificationAggregate {
    record Observation(String id, QualificationScenarios.Status status, String candidateSha256,
                       String evidenceSha256) {}
    private QualificationAggregate() {}

    static QualificationScenarios.Status assess(List<Observation> rows) {
        if (rows == null || rows.size() != QualificationScenarios.all().size())
            return QualificationScenarios.Status.blocked;
        Set<String> expected = new HashSet<>();
        for (var scenario : QualificationScenarios.all()) expected.add(scenario.id());
        Set<String> seen = new HashSet<>();
        String candidate = null;
        boolean unvalidatedOutcome = false;
        for (Observation row : rows) {
            if (row == null || !expected.contains(row.id()) || !seen.add(row.id()) || row.status() == null)
                return QualificationScenarios.Status.blocked;
            if (row.candidateSha256() != null) {
                if (!row.candidateSha256().matches("[0-9a-f]{64}")) return QualificationScenarios.Status.blocked;
                if (candidate == null) candidate = row.candidateSha256();
                else if (!candidate.equals(row.candidateSha256())) return QualificationScenarios.Status.blocked;
            }
            if (row.status() != QualificationScenarios.Status.blocked) {
                // A hex digest is an address, not authenticated outcome evidence.
                // A later reviewed validator must prove candidate, readiness and
                // test provenance BEFORE any non-blocked status can be trusted.
                unvalidatedOutcome = true;
                if (candidate == null || row.evidenceSha256() == null
                    || !row.evidenceSha256().matches("[0-9a-f]{64}"))
                    return QualificationScenarios.Status.blocked;
            }
        }
        if (!seen.equals(expected) || unvalidatedOutcome) return QualificationScenarios.Status.blocked;
        return QualificationScenarios.Status.blocked; // all mandatory production prerequisites remain absent
    }
}
