package com.campus.auth.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求。
 *
 * <p>account 支持三种登录方式（自动识别，无需前端切换）：</p>
 * <ul>
 *   <li>学号，如 {@code 2021001}</li>
 *   <li>手机号，如 {@code 13805011234}</li>
 *   <li>邮箱，如 {@code admin@campus.edu.cn}</li>
 * </ul>
 */
@Data
public class LoginReqDTO {

    /** 学号 / 手机号 / 邮箱 */
    @NotBlank(message = "账号不能为空")
    private String account;

    /** 密码（明文传输依赖 HTTPS；本机演示环境除外） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6~32 位之间")
    private String password;
}
