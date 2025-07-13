package dev.lukebemish.testingutils;

import dev.lukebemish.managedversioning.actions.GradleJob;
import dev.lukebemish.managedversioning.actions.Job;
import org.gradle.api.Action;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;
import org.jspecify.annotations.Nullable;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class TestingUtilsExtension {
    public abstract Property<String> getJavaVersion();
    public abstract ListProperty<String> getOnBranches();
    public abstract ListProperty<String> getGradleRoots();

    @Nested
    public abstract PlatformTesting getPlatform();

    public void platform(Action<PlatformTesting> action) {
        action.execute(getPlatform());
    }

    @Inject
    public TestingUtilsExtension() {
        getGradleRoots().convention(List.of("."));
        getJavaVersion().convention("21");
    }

    public static abstract class PlatformTesting {
        public abstract Property<Boolean> getEnabled();
        public abstract ListProperty<String> getJavaVersions();
        public abstract ListProperty<String> getArchitectures();
        public abstract ListProperty<String> getOperatingSystems();
        public abstract ListProperty<PlatformConfiguration> getExcludes();
        public abstract MapProperty<PlatformConfiguration, String> getRunners();
        public abstract Property<String> getTestEnvironmentProject();

        public record PlatformConfiguration(
            @Nullable String operatingSystem,
            @Nullable String architecture,
            @Nullable String javaVersion
        ) {}

        void configurePlatformTestJob(Job job) {
            var strategy = new LinkedHashMap<String, Object>();
            strategy.put("fail-fast", "false");
            var matrix = new LinkedHashMap<String, Object>();
            strategy.put("matrix", matrix);
            var operatingSystems = getOperatingSystems().get();
            var architectures = getArchitectures().get();
            var javaVersions = getJavaVersions().get();
            matrix.put("os", operatingSystems);
            matrix.put("arch", architectures);
            matrix.put("java", getJavaVersions().get());
            var excludes = new ArrayList<Map<String, String>>();
            var excludesList = getExcludes().get();
            for (PlatformConfiguration exclude : excludesList) {
                var excludeMap = new LinkedHashMap<String, String>();
                if (exclude.operatingSystem() != null) {
                    excludeMap.put("os", exclude.operatingSystem());
                }
                if (exclude.architecture() != null) {
                    excludeMap.put("arch", exclude.architecture());
                }
                if (exclude.javaVersion() != null) {
                    excludeMap.put("java", exclude.javaVersion());
                }
                excludes.add(excludeMap);
            }
            matrix.put("exclude", excludes);
            var includes = new ArrayList<Map<String, String>>();
            for (var runnerEntry : getRunners().get().entrySet()) {
                var platform = runnerEntry.getKey();
                if (platform.operatingSystem != null && !operatingSystems.contains(platform.operatingSystem)) {
                    continue;
                }
                if (platform.architecture != null && !architectures.contains(platform.architecture)) {
                    continue;
                }
                if (platform.javaVersion != null && !javaVersions.contains(platform.javaVersion)) {
                    continue;
                }
                if (excludesList.stream().anyMatch(excluded ->
                    (excluded.operatingSystem == null || platform.operatingSystem == null || excluded.operatingSystem.equals(platform.operatingSystem)) &&
                        (excluded.architecture == null || platform.architecture == null || excluded.architecture.equals(platform.architecture)) &&
                        (excluded.javaVersion == null || platform.javaVersion == null || excluded.javaVersion.equals(platform.javaVersion)))) {
                    continue;
                }
                var map = new LinkedHashMap<String, String>();
                if (platform.operatingSystem != null) {
                    map.put("os", platform.operatingSystem);
                }
                if (platform.architecture != null) {
                    map.put("arch", platform.architecture);
                }
                if (platform.javaVersion != null) {
                    map.put("java", platform.javaVersion);
                }
                map.put("runner", runnerEntry.getValue());
                includes.add(map);
            }
            matrix.put("include", includes);
            job.getParameters().put("strategy", strategy);
            job.getNeeds().add("make-test-environment");
            job.getRunsOn().set("${{ matrix.runner }}");

            job.step(step -> {
                step.getName().set("Download Test Environment");
                step.getId().set("download");
                step.getUses().set("actions/download-artifact@v4");
                step.getWith().put("name", "platform-test-environment");
            });
            job.step(step -> {
                // We use this instead of locating java locally, since some platforms may not have various java versions present
                step.getName().set("Setup Java");
                step.getId().set("setup-java");
                step.getUses().set("actions/setup-java@v4");
                step.getWith().put("java-version", "${{ matrix.java }}");
                step.getWith().put("distribution", "temurin");
            });
            job.step(step -> {
                step.getName().set("Run");
                step.getId().set("run");
                step.getParameters().put("shell", "bash");
                step.getRun().set("java @args.txt");
            });
            job.step(step -> {
                step.getName().set("Upload Results");
                step.getId().set("upload");
                step.getRunsOnError().set(true);
                step.getRequiredSteps().add("setup-java");
                step.getUses().set("actions/upload-artifact@v4");
                step.getWith().put("name", "test-results-platform-${{ matrix.os }}-${{ matrix.arch }}-${{ matrix.java }}");
                step.getWith().put("if-no-files-found", "error");
                step.getWith().put("path", "results/open-test-report.xml");
            });
        }

        void configureMakeTestEnvironment(GradleJob gradleJob) {
            gradleJob.getName().set("make-test-environment");
            var projectName = getTestEnvironmentProject().get();
            gradleJob.gradlew("Make Test Environment", List.of(projectName + (projectName.endsWith(":") ? "" : ":") + "testingUtilsMakeTestEnvironment"), gradlew -> {
                gradlew.getId().set("make-test-environment");
            });
            gradleJob.upload("platform-test-environment", List.of("build/testingUtils/platform/"+projectNameToPrefix(projectName)), upload -> {
                upload.getName().set("Upload Test Environment");
                upload.getWith().put("if-no-files-found", "error");
                upload.getWith().put("retention-days", "1");
            });
        }

        static String projectNameToPrefix(String projectName) {
            projectName = projectName.replace(':', '-');
            while (projectName.startsWith("-")) {
                projectName = projectName.substring(1);
            }
            while (projectName.endsWith("-")) {
                projectName = projectName.substring(0, projectName.length() - 1);
            }
            return projectName;
        }

        @Inject
        public PlatformTesting() {
            getEnabled().set(false);
            getOperatingSystems().convention(List.of("linux", "windows", "macos"));
            getArchitectures().convention(List.of("x86_64", "aarch64"));
            getExcludes().add(new PlatformConfiguration("macos", "x86_64", null));
            getRunners().putAll(Map.of(
                new PlatformConfiguration("linux", "x86_64", null), "ubuntu-latest",
                new PlatformConfiguration("linux", "aarch64", null), "ubuntu-24.04-arm",
                new PlatformConfiguration("windows", "x86_64", null), "windows-latest",
                new PlatformConfiguration("windows", "aarch64", null), "windows-11-arm",
                new PlatformConfiguration("macos", "aarch64", null), "macos-latest"
            ));
        }

        public void exclude(String operatingSystem, String architecture, String javaVersion) {
            getExcludes().add(new PlatformConfiguration(javaVersion, architecture, operatingSystem));
        }
    }
}
