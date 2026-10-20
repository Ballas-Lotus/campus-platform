package com.campus.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.user.dao.entity.UserMailDO;

/**
 * 邮箱索引查询（阶段 14）：selectById(email) 即单分片命中（分片键 = 主键 email）。
 */
public interface UserMailMapper extends BaseMapper<UserMailDO> {
}
