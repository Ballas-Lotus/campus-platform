package com.campus.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.user.dao.entity.UserDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper（MyBatis-Plus BaseMapper，无需 XML）。
 */
public interface UserMapper extends BaseMapper<UserDO> {

    /**
     * 行锁当前用户（SELECT ... FOR UPDATE）——把"同一用户"的并发操作串行化。
     *
     * <p>选课场景：防止"两个时间冲突的教学班并发同时选"绕过冲突检测
     * （冲突检测是"读后判定"，两请求同时读到旧课表会都通过）。</p>
     */
    @Select("SELECT id FROM t_user WHERE id = #{userId} FOR UPDATE")
    Long lockById(@Param("userId") Long userId);
}
