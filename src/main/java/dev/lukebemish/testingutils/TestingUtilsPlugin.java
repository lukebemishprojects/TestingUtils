package dev.lukebemish.testingutils;

import dev.lukebemish.managedversioning.Constants;
import dev.lukebemish.managedversioning.ManagedVersioningExtension;
import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;
import org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestFramework;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testing.base.TestingExtension;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;

public class TestingUtilsPlugin implements Plugin<Settings> {
    private static final String NAMESPACE_URI = "https://schemas.lukebemish.dev/testingutils/0.1.0";
    private static final String IMPL_VERSION = Objects.requireNonNull(TestingUtilsPlugin.class.getPackage().getImplementationVersion());

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public void apply(Settings settings) {
        var extension = settings.getExtensions().create("testingUtils", TestingUtilsExtension.class);
        settings.getGradle().getSharedServices().registerIfAbsent(SourceDirectoryWriterService.class.getName(), SourceDirectoryWriterService.class, spec -> {
            // Provide some parameters
            spec.getParameters().getOutputFile().set(settings.getRootDir().toPath().resolve("build/testingUtils/source-directories.txt").toFile());
        });
        settings.getGradle().getLifecycle().beforeProject(project -> {
            project.getExtensions().create("testingUtils", TestingUtilsProjectExtension.class);
            var writeSourceDirectories = project.getTasks().register("testingUtilsWriteSourceDirectories", WriteSourceDirectoriesTask.class);
            project.getPluginManager().withPlugin("java-base", applied -> {
                project.afterEvaluate(p -> {
                    var rootPath = p.getRootDir().toPath();
                    p.getExtensions().getByType(SourceSetContainer.class).configureEach(sourceSet -> {
                        for (var file : sourceSet.getAllSource().getSourceDirectories().getFiles()) {
                            var relativePath = rootPath.relativize(file.toPath()).toString().replace(File.separatorChar, '/');
                            writeSourceDirectories.get().getSourceDirectories().add(relativePath);
                        }
                    });
                });
            });
            var projectPath = project.getPath();
            project.getPluginManager().withPlugin("test-suite-base", applied -> {
                project.afterEvaluate(p ->
                    p.getExtensions().getByType(TestingExtension.class).getSuites().configureEach(suite -> {
                        if (suite instanceof JvmTestSuite jvmTestSuite) {
                            jvmTestSuite.dependencies(dependencies -> {
                                var depString = "dev.lukebemish.testingutils:framework";
                                depString = depString + ":" + IMPL_VERSION;
                                dependencies.getImplementation().add(
                                    depString
                                );
                            });
                            jvmTestSuite.getTargets().configureEach(target -> {
                                var singleTestsName = target.getTestTask().getName();
                                var workingDir = target.getTestTask().map(Test::getWorkingDir);
                                var outPath = workingDir.map(dir -> dir.toPath().resolve("build/testingUtils/raw/" + singleTestsName + "/open-test-report.xml").toFile());
                                var outPathProperty = p.getObjects().fileProperty();
                                outPathProperty.fileProvider(outPath);
                                var finalizerTask = p.getTasks().register(singleTestsName + "FinalizeOpenTestReport", task -> {
                                    var outputFile = p.getLayout().getBuildDirectory().file("testingUtils/out/" + singleTestsName + "-open-test-report.xml");
                                    task.getOutputs().file(outputFile);
                                    task.getInputs().file(outPathProperty);
                                    task.mustRunAfter(target.getTestTask());
                                    task.setEnabled(target.getTestTask().get().isEnabled());
                                    var candidates = target.getTestTask().get().getCandidateClassFiles();
                                    task.onlyIf(spec ->
                                        !candidates.isEmpty()
                                    );
                                    task.doLast(t -> {
                                        try (var is = Files.newInputStream(outPathProperty.get().getAsFile().toPath())) {
                                            var document = DocumentBuilderFactory.newNSInstance()
                                                .newDocumentBuilder()
                                                .parse(is);
                                            var events = document.getElementsByTagName("e:events");
                                            var element = (Element) events.item(0);
                                            element.setAttributeNS(
                                                "http://www.w3.org/2000/xmlns/",
                                                "xmlns:testingutils",
                                                NAMESPACE_URI
                                            );
                                            var infrastructure = document.getElementsByTagName("infrastructure");
                                            if (infrastructure.getLength() > 0) {
                                                var item = infrastructure.item(0);
                                                var idElement = document.createElementNS(NAMESPACE_URI, "testingutils:id");
                                                idElement.setTextContent(projectPath + (projectPath.endsWith(":") ? "" : ":") + singleTestsName);
                                                item.appendChild(idElement);
                                            }
                                            var transformer = TransformerFactory.newInstance().newTransformer();

                                            DOMSource source = new DOMSource(document);
                                            try (var os = Files.newOutputStream(outputFile.get().getAsFile().toPath())) {
                                                StreamResult result = new StreamResult(os);
                                                transformer.transform(source, result);
                                            }
                                        } catch (ParserConfigurationException | IOException | SAXException |
                                                 TransformerException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                                });
                                target.getTestTask().configure(test -> {
                                    if (test.getTestFramework() instanceof JUnitPlatformTestFramework) {
                                        test.systemProperty("junit.platform.reporting.open.xml.enabled", "true");
                                        test.systemProperty("junit.platform.reporting.output.dir", "build/testingUtils/raw/" + singleTestsName);
                                        test.reports(reports -> {
                                            reports.getHtml().getRequired().set(false);
                                            reports.getJunitXml().getRequired().set(false);
                                        });
                                        test.getOutputs().file(outPathProperty);
                                        test.finalizedBy(finalizerTask);
                                    }
                                });
                            });
                        }
                    })
                );
            });
        });
        settings.getPluginManager().withPlugin("dev.lukebemish.managedversioning", applied -> {
            var onBranches = extension.getOnBranches();
            var javaVersion = extension.getJavaVersion();
            var versioning = settings.getExtensions().getByType(ManagedVersioningExtension.class);
            var testProjects = extension.getGradleRoots();
            var platform = extension.getPlatform();
            versioning.gitHubActions(actions -> {
                actions.register("test", action -> {
                    action.getConcurrency().unsetConvention();
                    action.getConcurrency().unset();
                    action.getPrettyName().set("Run Tests");
                    action.getPullRequest().set(true);
                    action.getOnBranches().addAll(onBranches);
                    action.gradleJob(gradleJob -> {
                        gradleJob.getJavaVersion().set(javaVersion);
                        gradleJob.getName().set("check");
                        testProjects.get().forEach(directory -> {
                            gradleJob.gradlew("Check "+directory, List.of("check --continue"), gradlew -> {
                                gradlew.getWorkingDirectory().set(directory);
                                gradlew.getRunsOnError().set(true);
                            });
                        });
                        gradleJob.upload("test-results-gradle", List.of("**/testingUtils/out/*-open-test-report.xml"), upload -> {
                            upload.getName().set("Upload Results");
                            upload.getRunsOnError().set(true);
                            upload.getWith().put("retention-days", "1");
                        });
                        gradleJob.gradlew("Record Source Directories", List.of("testingUtilsWriteSourceDirectories"), step -> {
                            step.getRunsOnError().set(true);
                            step.getId().set("record-source-directories");
                        });
                        gradleJob.step(step -> {
                            step.getRunsOnError().set(true);
                            step.getRequiredSteps().add("record-source-directories");
                            step.getName().set("Capture Source Directories");
                            step.getId().set("capture-source-directories");
                            step.getRun().set("echo sourcedirectories=$(cat build/testingUtils/source-directories.txt) >> \"$GITHUB_OUTPUT\"");
                        });
                        gradleJob.getOutputs().put("sourcedirectories", "${{ steps.capture-source-directories.outputs.sourcedirectories }}");
                    });
                    action.job(job -> {
                        job.getName().set("summarize-test-results");
                        job.getRunsOn().set("ubuntu-latest");
                        job.getIf().set("always()");
                        job.getNeeds().add("check");
                        if (platform.getEnabled().get()) {
                            job.getNeeds().add("platform-test");
                        }
                        job.step(step -> {
                            step.getName().set("Setup Java");
                            step.getRun().set("echo \"JAVA_HOME=$JAVA_HOME_21_X64\" >> \"$GITHUB_ENV\"");
                        });
                        job.step(step -> {
                            step.getName().set("Checkout");
                            step.getUses().set(Constants.Versions.CHECKOUT);
                            step.getWith().put("path", "repository");
                        });
                        job.step(step -> {
                            step.getName().set("Download Test Results");
                            step.getUses().set(Constants.Versions.DOWNLOAD_ARTIFACT);
                            step.getWith().put("pattern", "test-results-*");
                            step.getWith().put("path", "results");
                        });
                        job.step(step -> {
                            step.getName().set("Download TestingUtils CLI");
                            step.getRun().set("curl -L -o testingutils-cli.jar https://github.com/lukebemishprojects/TestingUtils/releases/download/"+IMPL_VERSION+"/cli-"+IMPL_VERSION+"-all.jar");
                        });
                        job.step(step -> {
                            step.getName().set("Annotate Test Results");
                            step.getRun().set("""
                                find results -name '*-test-report.xml' > reports.txt;
                                $JAVA_HOME/bin/java -jar testingutils-cli.jar annotate repository ${{needs.check.outputs.sourcedirectories}} @reports.txt
                                """);
                        });
                    });
                    if (platform.getEnabled().get()) {
                        action.gradleJob(gradleJob -> {
                            gradleJob.getJavaVersion().set(javaVersion);
                            platform.configureMakeTestEnvironment(gradleJob);
                        });
                        action.job(job -> {
                            job.getName().set("platform-test");
                            platform.configurePlatformTestJob(job);
                        });
                    }
                });
            });
        });
    }
}
