package pub.longyi.ts3audiobot.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public final class AuthController {
    private static final String SESSION_ADMIN = "adminUser";
    private static final String COOKIE_REMEMBER = "ts3ab_remember";

    private final AdminService adminService;

    public AuthController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/setup")
    public String setup(Model model) {
        if (adminService.hasAdmin()) {
            return "redirect:/login";
        }
        model.addAttribute("hasAdmin", false);
        return "setup";
    }

    @PostMapping("/setup")
    public String createAdmin(
        @RequestParam String username,
        @RequestParam String password,
        @RequestParam String confirm,
        Model model,
        HttpSession session
    ) {
        if (adminService.hasAdmin()) {
            return "redirect:/login";
        }
        if (username == null || username.isBlank()) {
            model.addAttribute("error", "请输入账号");
            return "setup";
        }
        if (password == null || password.isBlank()) {
            model.addAttribute("error", "请输入密码");
            return "setup";
        }
        if (!password.equals(confirm)) {
            model.addAttribute("error", "两次密码不一致");
            return "setup";
        }
        String safeUsername = username.trim();
        adminService.createAdmin(safeUsername, password);
        session.setAttribute(SESSION_ADMIN, safeUsername);
        return "redirect:/";
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("hasAdmin", adminService.hasAdmin());
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(
        @RequestParam String username,
        @RequestParam String password,
        @RequestParam(name = "rememberMe", defaultValue = "false") boolean rememberMe,
        Model model,
        HttpSession session,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        if (!adminService.verify(username, password)) {
            model.addAttribute("error", "账号或密码错误");
            return "login";
        }
        String safeUsername = username == null ? "" : username.trim();
        session.setAttribute(SESSION_ADMIN, safeUsername);
        if (rememberMe) {
            String token = adminService.issueRememberToken(safeUsername);
            writeRememberCookie(response, request.isSecure(), token, adminService.rememberTokenMaxAgeSeconds());
        } else {
            clearRememberCookie(response, request.isSecure());
        }
        return "redirect:/";
    }

    @PostMapping("/logout")
    public String logout(
        HttpSession session,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        session.invalidate();
        clearRememberCookie(response, request.isSecure());
        return "redirect:/login";
    }

    private void writeRememberCookie(HttpServletResponse response, boolean secure, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(COOKIE_REMEMBER, value == null ? "" : value);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(Math.max(0, maxAgeSeconds));
        response.addCookie(cookie);
    }

    private void clearRememberCookie(HttpServletResponse response, boolean secure) {
        writeRememberCookie(response, secure, "", 0);
    }
}
