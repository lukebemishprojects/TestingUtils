package dev.lukebemish.testingutils.framework.modulelayer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

record LayerConfigurationOptions(
    List<String> imports,
    List<String> staticImports,
    List<String> requires,
    List<String> compilerArgs
) {
    LayerConfigurationOptions(List<LayerConfiguration> annotations) {
        this(
            Stream.concat(
                Stream.of("org.junit.jupiter.api.*"),
                annotations.stream().flatMap(a -> Stream.of(a.imports()))
            ).toList(),
            Stream.concat(
                Stream.of("org.junit.jupiter.api.Assertions.*"),
                annotations.stream().flatMap(a -> Stream.of(a.staticImports()))
            ).toList(),
            Stream.concat(
                Stream.of("org.junit.jupiter.api"),
                annotations.stream().flatMap(a -> Stream.of(a.requires()))
            ).toList(),
            annotations.stream().flatMap(a -> Stream.of(a.compilerArgs())).toList()
        );
    }
}
