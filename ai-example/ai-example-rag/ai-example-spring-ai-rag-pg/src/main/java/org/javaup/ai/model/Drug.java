package org.javaup.ai.model;

import lombok.Data;

/**
 * @program:
 * @description: 模型对象
 * @author:
 **/
@Data
public class Drug {

    private String id;
    private String name;
    private String indications;
    private String dosage;
    private String precautions;
    private String category;
}
