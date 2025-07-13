package dev.lukebemish.testingutils;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public abstract class SourceDirectoryWriterService implements BuildService<SourceDirectoryWriterService.Parameters> {
    public interface Parameters extends BuildServiceParameters {
        RegularFileProperty getOutputFile();
    }

    private boolean written = false;

    public synchronized void writeSourceDirectory(String path) throws IOException {
        var outPath = getParameters().getOutputFile().get().getAsFile().toPath();
        String contents;
        if (!written) {
            Files.deleteIfExists(outPath);
            written = true;
            contents = path;
        } else {
            contents = path + ":" + Files.readString(outPath);
        }
        Files.writeString(outPath, contents, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }
}
