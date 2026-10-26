package com.continuum.testsupport;

import java.util.ArrayList;
import java.util.List;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Garde-fou : quand {@code -Dit.db.url} est fourni, un test d'intégration ne doit pas être
 * neutralisé en silence (scripts du migrateur introuvables, base absente…). Sinon le build resterait
 * vert avec 0 test réellement exécuté. Dans ce cas la JVM de test termine en échec, bruyamment.
 */
public class DbSkipGuardListener implements TestExecutionListener {

    private final List<String> silentSkips = new ArrayList<>();

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (result.getStatus() != TestExecutionResult.Status.ABORTED || !dbRequested()) {
            return;
        }
        String reason = result.getThrowable().map(Throwable::getMessage).orElse("");
        if (reason != null && (reason.contains("introuvable") || reason.contains("Base de test absente"))) {
            silentSkips.add(id.getDisplayName() + " — " + reason);
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan plan) {
        if (silentSkips.isEmpty()) {
            return;
        }
        System.err.println("FATAL: -Dit.db.url is set but " + silentSkips.size()
                + " integration test(s) were silently skipped:");
        silentSkips.forEach(s -> System.err.println("  - " + s));
        System.err.flush();
        Runtime.getRuntime().halt(1);
    }

    private static boolean dbRequested() {
        String url = System.getProperty("it.db.url");
        return url != null && !url.isBlank() && !url.startsWith("${");
    }
}
