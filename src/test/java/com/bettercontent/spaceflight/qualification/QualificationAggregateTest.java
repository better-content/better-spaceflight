package com.bettercontent.spaceflight.qualification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class QualificationAggregateTest {
    private static final String CANDIDATE = "a".repeat(64), OTHER = "b".repeat(64), EVIDENCE = "c".repeat(64);
    private static List<QualificationAggregate.Observation> rows(QualificationScenarios.Status status) {
        return QualificationScenarios.all().stream().map(s -> new QualificationAggregate.Observation(
            s.id(), status, CANDIDATE,
            status == QualificationScenarios.Status.passed || status == QualificationScenarios.Status.failed
                ? EVIDENCE : null)).toList();
    }
    @Test void noProductionRouteAndNoExecutedScenarioCannotBecomePass() {
        var pending = QualificationScenarios.pending();
        var observations = pending.stream().map(p -> new QualificationAggregate.Observation(
            p.scenario().id(), p.status(), null, null)).toList();
        assertEquals(QualificationScenarios.Status.blocked, QualificationAggregate.assess(observations));
        assertEquals(QualificationScenarios.Status.blocked, QualificationAggregate.assess(List.of()));
        assertEquals(QualificationScenarios.Status.blocked,
            QualificationAggregate.assess(rows(QualificationScenarios.Status.not_run)));
    }
    @Test void syntheticAllPassedCannotBypassMissingEvidenceValidator() {
        assertEquals(QualificationScenarios.Status.blocked,
            QualificationAggregate.assess(rows(QualificationScenarios.Status.passed)));
        var noEvidence = new ArrayList<>(rows(QualificationScenarios.Status.passed));
        var first = noEvidence.get(0);
        noEvidence.set(0, new QualificationAggregate.Observation(first.id(), first.status(), CANDIDATE, null));
        assertEquals(QualificationScenarios.Status.blocked, QualificationAggregate.assess(noEvidence));
    }
    @Test void candidateDriftMissingDuplicateAndUnknownIdsFailClosed() {
        var changed = new ArrayList<>(rows(QualificationScenarios.Status.passed));
        var first = changed.get(0);
        changed.set(0, new QualificationAggregate.Observation(first.id(), first.status(), OTHER, EVIDENCE));
        assertEquals(QualificationScenarios.Status.blocked, QualificationAggregate.assess(changed));
        changed.set(0, new QualificationAggregate.Observation(first.id(), first.status(), CANDIDATE, EVIDENCE));
        changed.set(1, changed.get(0));
        assertEquals(QualificationScenarios.Status.blocked, QualificationAggregate.assess(changed));
        changed.set(1, new QualificationAggregate.Observation("unknown", first.status(), CANDIDATE, EVIDENCE));
        assertEquals(QualificationScenarios.Status.blocked, QualificationAggregate.assess(changed));
        changed.remove(1);
        assertEquals(QualificationScenarios.Status.blocked, QualificationAggregate.assess(changed));
    }
    @Test void unvalidatedFailureDigestIsNotAuthenticFailureEvidence() {
        assertEquals(QualificationScenarios.Status.blocked,
            QualificationAggregate.assess(rows(QualificationScenarios.Status.failed)));
        var missingEvidence = new ArrayList<>(rows(QualificationScenarios.Status.failed));
        var first = missingEvidence.get(0);
        missingEvidence.set(0, new QualificationAggregate.Observation(first.id(), first.status(), CANDIDATE, null));
        assertEquals(QualificationScenarios.Status.blocked, QualificationAggregate.assess(missingEvidence));
    }
}
