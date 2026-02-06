package pub.longyi.ts3audiobot.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 WebConfig 相关功能。
 */


/**
 * WebConfig 相关功能。
 *
 * <p>职责：负责 WebConfig 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AdminService adminService;


    /**
     * 创建 WebConfig 实例。
     * @param adminService 参数 adminService
     */
    public WebConfig(AdminService adminService) {
        this.adminService = adminService;
    }


    /**
     * 执行 addInterceptors 操作。
     * @param registry 参数 registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(adminService));
    }
}
