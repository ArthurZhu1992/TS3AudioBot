package pub.longyi.ts3audiobot.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 AuthController 相关功能。
 */


/**
 * AuthController 相关功能。
 *
 * <p>职责：负责 AuthController 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Controller
public final class AuthController {
    private static final String SESSION_ADMIN = "adminUser";
    private final AdminService adminService;


    /**
     * 创建 AuthController 实例。
     * @param adminService 参数 adminService
     */
    public AuthController(AdminService adminService) {
        this.adminService = adminService;
    }


    /**
     * 执行 setup 操作。
     * @param model 参数 model
     * @return 返回值
     */
    @GetMapping("/setup")
    public String setup(Model model) {
        if (adminService.hasAdmin()) {
            return "redirect:/login";
        }
        model.addAttribute("hasAdmin", false);
        return "setup";
    }


    /**
     * 执行 createAdmin 操作。
     * @param username 参数 username
     * @param password 参数 password
     * @param confirm 参数 confirm
     * @param model 参数 model
     * @param session 参数 session
     * @return 返回值
     */
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
        adminService.createAdmin(username.trim(), password);
        session.setAttribute(SESSION_ADMIN, username.trim());
        return "redirect:/";
    }


    /**
     * 执行 login 操作。
     * @param model 参数 model
     * @return 返回值
     */
    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("hasAdmin", adminService.hasAdmin());
        return "login";
    }


    /**
     * 执行 doLogin 操作。
     * @param username 参数 username
     * @param password 参数 password
     * @param model 参数 model
     * @param session 参数 session
     * @return 返回值
     */
    @PostMapping("/login")
    public String doLogin(
        @RequestParam String username,
        @RequestParam String password,
        Model model,
        HttpSession session
    ) {
        if (!adminService.verify(username, password)) {
            model.addAttribute("error", "账号或密码错误");
            return "login";
        }
        session.setAttribute(SESSION_ADMIN, username.trim());
        return "redirect:/";
    }


    /**
     * 执行 logout 操作。
     * @param session 参数 session
     * @return 返回值
     */
    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }
}
