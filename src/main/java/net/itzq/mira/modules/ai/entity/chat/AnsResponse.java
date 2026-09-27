package net.itzq.mira.modules.ai.entity.chat;

import lombok.Data;
import net.itzq.mira.core.utils.json.JsonObject;

/**
 *  Response
 *
 *  @author tangzq
 */
@Data
public class AnsResponse {

    String type;

    String answer;

    String historyId;

    String agentId;

    String parentAgentId;

    String agentName;

    String roundId;

    String msgId;

    JsonObject info;

}
