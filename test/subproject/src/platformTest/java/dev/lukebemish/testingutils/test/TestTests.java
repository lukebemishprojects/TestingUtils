package dev.lukebemish.testingutils.test;

import dev.lukebemish.testingutils.framework.modulelayer.LayerBuilder;
import dev.lukebemish.testingutils.framework.modulelayer.LayerTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestTests implements TestInheritanceInterface {
    @LayerTest
    LayerBuilder testLayerBuilder() {
        return LayerBuilder.create()
            .withModule("test.test", module -> module
                .test("test.test.TestStuff", """
                    @Test
                    void testImportsWork() {
                        Function<String, String> function = s -> s;
                    }"""));
    }

    @Test
    void testAlwaysPass() {}
}
