package org.example.bot;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "钉钉机器人配置。绑定到一个 Agent 后，会自动启动 DingTalk Channel；变更后立即生效，无需重启。")
public class DingTalkBotConfig {

    @Schema(description = "是否启用机器人。关闭时会停止 Channel 并清除配置", example = "true")
    private boolean enabled;

    @Schema(description = "钉钉开放平台应用的 AppKey",
            example = "dingxxxxxxxxxxxx")
    private String appKey;

    @Schema(description = "钉钉开放平台应用的 AppSecret（列表/查询时掩码为 ***）",
            example = "secret_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
    private String appSecret;

    @Schema(description = "机器人 RobotCode（在钉钉开放平台「机器人」页获取）", example = "dingxxxxxxxxxxxx")
    private String robotCode;

    public DingTalkBotConfig() {}

    public DingTalkBotConfig(boolean enabled, String appKey, String appSecret, String robotCode) {
        this.enabled = enabled;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.robotCode = robotCode;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }

    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }

    public String getRobotCode() { return robotCode; }
    public void setRobotCode(String robotCode) { this.robotCode = robotCode; }

    @JsonIgnore
    public boolean isComplete() {
        return appKey != null && !appKey.isBlank()
                && appSecret != null && !appSecret.isBlank()
                && robotCode != null && !robotCode.isBlank();
    }

    /** 返回脱敏副本：enabled=true 且字段齐时，appSecret 替换为 ***。其他字段原样返回。 */
    public DingTalkBotConfig masked() {
        DingTalkBotConfig m = new DingTalkBotConfig();
        m.enabled = this.enabled;
        m.appKey = this.appKey;
        m.appSecret = (this.appSecret == null || this.appSecret.isBlank() || MASKED.equals(this.appSecret))
                ? this.appSecret
                : MASKED;
        m.robotCode = this.robotCode;
        return m;
    }

    @JsonIgnore
    public static final String MASKED = "***";
}