module dev.lukebemish.testingutils.framework {
    requires static org.jspecify;
    requires static org.jetbrains.annotations;
    requires static com.google.auto.service;

    requires org.junit.platform.launcher;
    requires org.junit.jupiter.api;
    requires java.compiler;

    exports dev.lukebemish.testingutils.framework.modulelayer;

    provides org.junit.platform.engine.TestEngine with dev.lukebemish.testingutils.framework.modulelayer.ModuleLayerEngine;
}
