package org.javaup.ai.chatagent.dto;

import lombok.Data;

/**
 * @program:
 * @description: 数据传输对象
 * @author:
 **/

@Data
public class ConversationSessionListQueryDto {

    private String keyword;

    private String chatMode;

    private String turnStatus;

    private String pageNo;

    private String pageSize;
}
