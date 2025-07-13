package dev.lukebemish.testingutils;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

import javax.inject.Inject;

public abstract class TestingUtilsProjectExtension {
    @Inject
    public TestingUtilsProjectExtension() {}

    @Inject
    protected abstract Project getProject();

    public TaskProvider<MakeTestingEnvironmentTask> makeTestingEnvironment(SourceSet sourceSet, Action<MakeTestingEnvironmentTask> action) {
        var implVersion = TestingUtilsPlugin.class.getPackage().getImplementationVersion();
        var depString = "dev.lukebemish.testingutils:framework";
        if (implVersion != null) {
            depString = depString + ":" + implVersion;
        }
        getProject().getDependencies().add(sourceSet.getImplementationConfigurationName(), depString);
        var makeTestEnvironment = getProject().getTasks().register("testingUtilsMakeTestEnvironment", MakeTestingEnvironmentTask.class, task -> {
            task.getOutputDirectory().set(
                getProject().getRootDir().toPath()
                    .resolve("build/testingUtils/platform")
                    .resolve(TestingUtilsExtension.PlatformTesting.projectNameToPrefix(getProject().getPath()))
                    .toFile());
            task.getClasspath().from(getProject().getConfigurations().getByName(sourceSet.getRuntimeClasspathConfigurationName()));
            task.getClasspath().from(getProject().getConfigurations().getByName(sourceSet.getRuntimeElementsConfigurationName()).getAllArtifacts().getFiles());
            action.execute(task);
        });
        var runEnv = getProject().getTasks().register("testingUtilsRunTestEnvironment", JavaExec.class);
        getProject().afterEvaluate(p -> {
            runEnv.configure(task -> {
                task.dependsOn(makeTestEnvironment);
                task.workingDir(makeTestEnvironment.flatMap(MakeTestingEnvironmentTask::getOutputDirectory));
                task.classpath(makeTestEnvironment.get().getClasspath());
                task.jvmArgs(makeTestEnvironment.get().argsExceptLaunch().get());
                if (makeTestEnvironment.get().getModular().get()) {
                    task.jvmArgs("--add-modules=ALL-MODULE-PATH");
                    task.getMainModule().set("dev.lukebemish.testingutils.framework");
                    task.getModularity().getInferModulePath().set(true);
                } else {
                    task.getMainClass().set("dev.lukebemish.testingutils.framework.Framework");
                    task.getModularity().getInferModulePath().set(false);
                }
            });
        });
        return makeTestEnvironment;
    }
}
