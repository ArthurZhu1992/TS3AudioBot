package pub.longyi.ts3audiobot.queue;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 璐熻矗 Track 鐩稿叧鍔熻兘銆? */


/**
 * Track 鐩稿叧鍔熻兘銆? *
 * <p>鑱岃矗锛氳礋璐?Track 鐩稿叧鍔熻兘銆?/p>
 * <p>绾跨▼瀹夊叏锛氭棤鏄惧紡淇濊瘉銆?/p>
 * <p>绾︽潫锛氳皟鐢ㄦ柟闇€閬靛畧鏂规硶濂戠害銆?/p>
 */
public record Track(
    String id,
    String title,
    String sourceType,
    String sourceId,
    String streamUrl,
    long durationMs,
    String coverUrl,
    String artist,
    Long playCount
) {
}
