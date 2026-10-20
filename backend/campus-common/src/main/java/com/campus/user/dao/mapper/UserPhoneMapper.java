package com.campus.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.user.dao.entity.UserPhoneDO;

/**
 * 手机号索引查询（阶段 14）：selectById(phone) 即单分片命中（分片键 = 主键 phone）。
 */
public interface UserPhoneMapper extends BaseMapper<UserPhoneDO> {
}
