package net.itzq.mira.modules.vkb;

import lombok.Data;
import net.itzq.mira.modules.vkb.provider.EmbeddingProvider;

/**
 * VKB 配置类
 *
 * @author tangzq
 */
@Data
public class VKBConfig {

    /** 数据根目录 */
    private String dataDir = VKBConstants.DEFAULT_DATA_DIR;

    /** sqlite-vec 扩展库目录 */
    private String sqliteVecLibsDir = "./libs";

    /** Lucene 搜索返回结果数 */
    private int luceneTopN = VKBConstants.DEFAULT_LUCENE_TOP_N;


}
