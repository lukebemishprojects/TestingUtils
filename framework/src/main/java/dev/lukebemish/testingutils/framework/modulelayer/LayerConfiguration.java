package dev.lukebemish.testingutils.framework.modulelayer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Repeatable(LayerConfigurations.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface LayerConfiguration {
    String[] imports() default {};
    String[] staticImports() default {};
    String[] requires() default {};
    String[] compilerArgs() default {};
}
