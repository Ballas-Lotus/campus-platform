package com.campus.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户服务 · 启动类（阶段 12 拆分，端口 9081）。
 *
 * <p>职责：登录（三方式）/ 用户资料 / 个人中心（统计 + 当日日程 + 改密）。</p>
 * <p>扫描范围：本服务切片 + 共享内核（framework：UserTransmitFilter/LoginInterceptor/JWTUtil；
 * config：MyBatis/Jackson/拦截器注册）——不包含业务服务的任何组件。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.campus.user",
        "com.campus.auth",
        "com.campus.common",
        "com.campus.framework",
        "com.campus.config",
        // 共享内核中的 PenaltyService（com.campus.reservation.service，个人中心禁约状态要用）；
        // 其他 reservation 包内容不在本服务 classpath（在 campus-service 模块），扫描安全
        "com.campus.reservation"
})
@MapperScan("com.campus.**.dao.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
