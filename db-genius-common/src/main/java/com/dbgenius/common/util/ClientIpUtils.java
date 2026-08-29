package com.dbgenius.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析工具。
 *
 * <p>解析顺序：{@code X-Forwarded-For} 首跳（反向代理链路的最左端）→
 * {@code X-Real-IP} → {@code request.getRemoteAddr()}。
 * 前端不传 IP（浏览器拿不到真实公网 IP），统一由后端从代理头解析。</p>
 */
public final class ClientIpUtils {

    private ClientIpUtils() {
        // 工具类禁止实例化
    }

    /**
     * 解析客户端真实 IP。
     *
     * @param request 当前请求，可为 null（返回 null）
     * @return 客户端 IP；代理头缺失时回退为直连对端地址
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (hasText(ip)) {
            // X-Forwarded-For 形如 "client, proxy1, proxy2"，首跳才是真实客户端
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (hasText(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value.trim());
    }
}
