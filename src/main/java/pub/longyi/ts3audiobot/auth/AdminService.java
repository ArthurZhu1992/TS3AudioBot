package pub.longyi.ts3audiobot.auth;

import org.springframework.stereotype.Component;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 AdminService 相关功能。
 */


/**
 * AdminService 相关功能。
 *
 * <p>职责：负责 AdminService 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Component
public final class AdminService {
    private final AdminStore adminStore;


    /**
     * 创建 AdminService 实例。
     * @param configService 参数 configService
     */
    public AdminService(pub.longyi.ts3audiobot.config.ConfigService configService) {
        this.adminStore = new AdminStore(configService.getConfigStore().getDbPath());
        this.adminStore.initialize();
    }


    /**
     * 执行 hasAdmin 操作。
     * @return 返回值
     */
    public boolean hasAdmin() {
        return adminStore.hasAdmin();
    }


    /**
     * 执行 createAdmin 操作。
     * @param username 参数 username
     * @param password 参数 password
     */
    public void createAdmin(String username, String password) {
        PasswordHasher.PasswordHash hash = PasswordHasher.hash(password);
        adminStore.createAdmin(username, hash.saltBase64(), hash.hashBase64());
    }


    /**
     * 执行 verify 操作。
     * @param username 参数 username
     * @param password 参数 password
     * @return 返回值
     */
    public boolean verify(String username, String password) {
        AdminStore.AdminRecord record = adminStore.getAdmin(username);
        if (record == null) {
            return false;
        }
        return PasswordHasher.verify(password, record.salt(), record.hash());
    }
}
