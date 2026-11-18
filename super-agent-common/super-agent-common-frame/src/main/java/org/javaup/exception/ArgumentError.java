package org.javaup.exception;

import lombok.Data;

/**
 * @program: 企业级别深度设计 AI Agent。添加  微信，添加时备注 super 来获取项目的完整资料
 * @description: 异常类
 * @author:
 **/

@Data
public class ArgumentError {

	private String argumentName;

	private String message;
}
