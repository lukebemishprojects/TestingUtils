package dev.lukebemish.testingutils.framework;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.Filter;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.platform.engine.discovery.DiscoverySelectors.*;
import static org.junit.platform.launcher.EngineFilter.*;

public class Framework {
    public static void main(String[] args) {
        var selectors = new ArrayList<DiscoverySelector>();
        var filters = new ArrayList<Filter<?>>();
        var testModules = System.getProperty("dev.lukebemish.testingutils.framework.include-modules", "");
        if (!testModules.isBlank()) {
            for (String module : testModules.split(",")) {
                selectors.add(selectModule(module.trim()));
            }
        }
        var testPackages = System.getProperty("dev.lukebemish.testingutils.framework.include-packages", "");
        if (!testPackages.isBlank()) {
            for (String pkg : testPackages.split(",")) {
                selectors.add(selectPackage(pkg.trim()));
            }
        }
        var includeEngines = System.getProperty("dev.lukebemish.testingutils.framework.include-engines", "");
        if (!includeEngines.isBlank()) {
            for (String engine : includeEngines.split(",")) {
                filters.add(includeEngines(engine.trim()));
            }
        }

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectors)
            .filters(filters.toArray(Filter[]::new))
            .configurationParameter("junit.platform.reporting.open.xml.enabled", "true")
            .configurationParameter("junit.platform.reporting.output.dir", "results")
            .build();

        try (LauncherSession session = LauncherFactory.openSession()) {
            TestPlan testPlan = session.getLauncher().discover(request);

            var summaryGeneratingService = new SummaryGeneratingListener();
            AtomicBoolean hasFailures = new AtomicBoolean(false);

            var errorPrintingService = new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
                    if (testExecutionResult.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                        hasFailures.set(true);
                        testExecutionResult.getThrowable().ifPresentOrElse(
                            throwable -> {
                                System.err.println(testIdentifier.getDisplayName()+": failed with " + throwable);
                                throwable.printStackTrace(System.err);
                            },
                            () -> System.err.println(testIdentifier.getDisplayName()+": failed")
                        );
                    }
                    TestExecutionListener.super.executionFinished(testIdentifier, testExecutionResult);
                }
            };

            session.getLauncher().registerTestExecutionListeners(summaryGeneratingService, errorPrintingService);

            session.getLauncher().execute(testPlan);
            summaryGeneratingService.getSummary().printTo(new PrintWriter(System.out));

            if (hasFailures.get()) {
                throw new RuntimeException("Some tests failed. See the output above for details.");
            }

            if (summaryGeneratingService.getSummary().getTestsFoundCount() < 1) {
                throw new RuntimeException("No tests were found");
            }
        }
    }
}
