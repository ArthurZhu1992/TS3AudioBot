package pub.longyi.ts3audiobot.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

public final class AuthInterceptor implements HandlerInterceptor {
    private static final String SESSION_ADMIN = "adminUser";
    private static final String COOKIE_REMEMBER = "ts3ab_remember";

    private final AdminService adminService;

    public AuthInterceptor(AdminService adminService) {
        this.adminService = adminService;
    }

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
        if (path.startsWith("/setup") || path.startsWith("/login") || path.startsWith("/error")) {
            return true;
        }
        if (!adminService.hasAdmin()) {
            response.sendRedirect("/setup");
            return false;
        }

        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_ADMIN) != null) {
            return true;
        }

        String rememberedUser = adminService.verifyRememberToken(readCookie(request, COOKIE_REMEMBER));
        if (!rememberedUser.isBlank()) {
            request.getSession(true).setAttribute(SESSION_ADMIN, rememberedUser);
            return true;
        }

        if (path.startsWith("/internal")) {
            response.setStatus(401);
            return false;
        }
        response.sendRedirect("/login");
        return false;
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request == null || name == null || name.isBlank()) {
            return "";
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return "";
        }
        for (Cookie cookie : cookies) {
            if (cookie == null) {
                continue;
            }
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null ? "" : value.trim();
            }
        }
        return "";
    }
}
