package com.campus.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一分页响应（提示词第 7 节：分页统一 pageNum / pageSize，返回 total / list）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageRespDTO<T> {

    /** 总条数 */
    private Long total;

    /** 当前页数据 */
    private List<T> list;
}
