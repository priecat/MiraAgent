package net.itzq.mira.modules.ai.agent;

import net.itzq.mira.modules.ai.persistence.AbstractHistoryPersist;

/**
 *
 * @discription
 *
 * @created 2026/8/16 17:06
 */
public interface IBasicAgent {

    AgentContextHolder getContextHolder();

    String getAgentId();

    AbstractHistoryPersist getHistoryPersist();

}
