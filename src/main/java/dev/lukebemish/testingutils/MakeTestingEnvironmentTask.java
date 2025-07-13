package dev.lukebemish.testingutils;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.Nullable;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public abstract class MakeTestingEnvironmentTask extends DefaultTask {
    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory();

    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getClasspath();

    @Input
    public abstract ListProperty<String> getJvmArgs();

    @Input
    public abstract ListProperty<String> getTestModules();

    @Input
    public abstract ListProperty<String> getTestPackages();

    @Input
    public abstract ListProperty<String> getIncludeEngines();

    @Input
    public abstract Property<Boolean> getModular();

    @Inject
    public MakeTestingEnvironmentTask() {
        getModular().convention(false);
    }

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void run() throws IOException {
        var outputPath = getOutputDirectory().get().getAsFile().toPath();
        Files.walkFileTree(outputPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, @Nullable IOException exception) throws IOException {
                if (exception == null) {
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                } else {
                    throw exception;
                }
            }
        });
        Files.createDirectories(outputPath);
        getFileSystemOperations().copy(spec -> {
            spec.from(getClasspath());
            spec.into(getOutputDirectory().get().dir("libraries"));
        });
        var argFile = outputPath.resolve("args.txt");
        var args = new ArrayList<>(argsExceptLaunch().get());
        if (getModular().get()) {
            args.add("--module-path");
            args.add("libraries");
            args.add("--add-modules=ALL-MODULE-PATH");
            args.add("--module");
            args.add("dev.lukebemish.testingutils.framework");
        } else {
            args.add("--class-path");
            args.add("libraries");
            args.add("--main-class");
            args.add("dev.lukebemish.testingutils.framework.Framework");
        }
        Files.writeString(argFile, String.join(" ", args));
    }

    Provider<List<String>> argsExceptLaunch() {
        BiFunction<List<String>, List<String>, List<String>> concat = (a, b) -> {
            var result = new ArrayList<>(a);
            result.addAll(b);
            return result;
        };
        Provider<List<String>> args = getJvmArgs();
        args = args.zip(
            getTestPackages().<List<String>>map(packages -> {
                if (packages.isEmpty()) {
                    return new ArrayList<>();
                } else {
                    return new ArrayList<>(List.of("-Ddev.lukebemish.testingutils.framework.include-packages=" + String.join(",", packages)));
                }
            }),
            concat
        );
        args = args.zip(
            getIncludeEngines().<List<String>>map(engines -> {
                if (engines.isEmpty()) {
                    return new ArrayList<>();
                } else {
                    return new ArrayList<>(List.of("-Ddev.lukebemish.testingutils.framework.include-engines=" + String.join(",", engines)));
                }
            }),
            concat
        );
        args = args.zip(
            getTestModules().<List<String>>map(modules -> {
                if (modules.isEmpty()) {
                    return new ArrayList<>();
                } else {
                    return new ArrayList<>(List.of("-Ddev.lukebemish.testingutils.framework.include-modules=" + String.join(",", modules)));
                }
            }),
            concat
        );
        return args;
    }
}
