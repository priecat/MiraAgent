package net.itzq.mira.modules.ai.client.embedding;

/**
 * Embedding 向量化接口
 * 由调用方实现，vkb 不关心具体模型
 *
 * @author tangzq
 */
public interface EmbeddingProvider {

    /**
     * 将文本转换为向量
     *
     * @param text 输入文本
     * @return 向量数组，null 表示失败
     */
    float[] embed(String text);

    /**
     * 获取向量维度
     */
    int getDimension();
}
