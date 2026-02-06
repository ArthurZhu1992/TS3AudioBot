package pub.longyi.ts3audiobot.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 AuthInterceptor 相关功能。
 */


/**
 * AuthInterceptor 相关功能。
 *
 * <p>职责：负责 AuthInterceptor 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class AuthInterceptor implements HandlerInterceptor {
    private static final String SESSION_ADMIN = "adminUser";
    private final AdminService adminService;


    /**
     * 创建 AuthInterceptor 实例。
     * @param adminService 参数 adminService
     */
    public AuthInterceptor(AdminService adminService) {
        this.adminService = adminService;
    }


    /**
     * 执行 preHandle 操作。
     * @param request 参数 request
     * @param response 参数 response
     * @param handler 参数 handler
     * @return 返回值
     * @throws Exception 异常说明
     */
    @Override
    public boolean preHandle(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler
    ) throws Exception {
        String path = request.getRequestURI();
        if (path.startsWith("/css") || path.startsWith("/js") || path.startsWith("/images")) {

            return true;
        }
        if (path.startsWith("/setup") || path.startsWith("/login")) {
            return true;
        }
        if (path.startsWith("/error")) {
            return true;
        }
        if (!adminService.hasAdmin()) {
            response.sendRedirect("/setup");
            return false;
        }
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SESSION_ADMIN) == null) {
            if (path.startsWith("/internal")) {
                response.setStatus(401);
                return false;
            }
            response.sendRedirect("/login");
            return false;
        }
        return true;
    }
}
