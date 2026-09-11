package net.itzq.mira.modules.ai.tool;

import java.lang.reflect.Method;
import java.util.List;

/**
 *  AiTool Definition
 *
 *  @author tangzq
 */

public interface AiToolDefine {

    /**
     * 工具唯一名称
     */
    String name();

    /*
     * 展示名（UI 用）
     * */
    String display();

    /*
     * 给模型看的描述
     */
    String description();

    /**
     * 参数声明
     */
    List<AiToolParam> parameters();

    /*
     * 是否是 sub agent
     */
    default boolean subAgent() {
        return false;
    }

    /**
     * 真正执行的目标方法
     */
    Method executeMethod();
}
