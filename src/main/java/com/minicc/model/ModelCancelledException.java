package com.minicc.model;

/** 模型请求在进行中被用户取消（M9）。控制循环据此按“取消”而不是“错误”收尾，且不写入任何半截消息。 */
public class ModelCancelledException extends RuntimeException {
    public ModelCancelledException() {
        super("模型请求已取消");
    }
}
