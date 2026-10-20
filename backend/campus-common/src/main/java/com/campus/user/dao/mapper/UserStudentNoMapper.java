package com.campus.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.user.dao.entity.UserStudentNoDO;

/**
 * 学号索引查询（阶段 14）：selectById(studentNo) 即单分片命中（分片键 = 主键 student_no）。
 */
public interface UserStudentNoMapper extends BaseMapper<UserStudentNoDO> {
}
