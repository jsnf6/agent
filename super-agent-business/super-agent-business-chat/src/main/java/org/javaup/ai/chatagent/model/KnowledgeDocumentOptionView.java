package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program:
 * @description: 视图对象
 * @author:
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentOptionView {

    private String documentId;
    private String documentName;
    private String knowledgeScopeName;
    private String businessCategory;
    private String documentTags;
}
