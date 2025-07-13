package dev.lukebemish.testingutils.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestTests {
    @Test
    void testAlwaysPass() {}

    @Test
    void testAlwaysFail() {
        fail();
    }
}
