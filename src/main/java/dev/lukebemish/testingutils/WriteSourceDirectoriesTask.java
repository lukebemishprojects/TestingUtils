package dev.lukebemish.testingutils;

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import java.io.IOException;

@UntrackedTask(because = "Does not make sense to cache")
public abstract class WriteSourceDirectoriesTask extends DefaultTask {
    @Input
    public abstract ListProperty<String> getSourceDirectories();

    @ServiceReference("dev.lukebemish.testingutils.SourceDirectoryWriterService")
    protected abstract Property<SourceDirectoryWriterService> getSourceDirectoryWriterService();

    @TaskAction
    public void writeSourceDirectories() throws IOException {
        for (var path : getSourceDirectories().get()) {
            getSourceDirectoryWriterService().get().writeSourceDirectory(path);
        }
    }
}
