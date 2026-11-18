package org.javaup.ai.support;

import java.util.Collection;

import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

/**
 * @program:
 * @description: 支撑组件
 * @author:
 **/
public class ResettableMemorySaver extends MemorySaver {

    public int clearThread(String threadId) {
        Collection<Checkpoint> removed = remove(threadId);
        return removed != null ? removed.size() : 0;
    }

}
