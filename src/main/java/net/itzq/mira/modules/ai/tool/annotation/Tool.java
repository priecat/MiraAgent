package net.itzq.mira.modules.ai.tool.annotation;

import java.lang.annotation.*;

/**
 *  Tool
 *
 *  @author tangzq
 */
@Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {

    /**
     * The name of the tool. If not provided, the method name will be used.
     */
    String name() default "";

    String display() default "";

    boolean subAgent() default false;

    /**
     * The description of the tool. If not provided, the method name will be used.
     */
    String description() default "";

    /**
     * Whether the tool result should be returned directly or passed back to the model.
     */
    boolean returnDirect() default false;

}
