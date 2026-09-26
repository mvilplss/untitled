package org.example.dws;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dws")
public class DwsProperties {

    /** dws 二进制版本号（pin 住避免自动升级引入不兼容） */
    private String version = "v1.0.62";

    /** 显式指定的 dws 二进制绝对路径；为空时按 env → userData/bin → PATH 顺序探测 */
    private String binaryPath = "";

    /** GitHub release asset 下载基础 URL */
    private String downloadBaseUrl = "https://github.com/DingTalk-Real-AI/dingtalk-workspace-cli/releases/download";

    /** 二进制文件落地的子目录（相对 userData.binDir） */
    private String binaryFilename = "dws";

    /** dws 配置 + 用户目录根路径（绝对路径） */
    private String configRoot = ".agentscope/.dws";

    /** 进程级 dws 调用默认超时（毫秒） */
    private long defaultTimeoutMs = 30_000L;

    /** 单次 dws 子进程允许的最大 stdout 字节数（防止 OOM） */
    private int maxOutputBytes = 8 * 1024 * 1024;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getBinaryPath() { return binaryPath; }
    public void setBinaryPath(String binaryPath) { this.binaryPath = binaryPath; }

    public String getDownloadBaseUrl() { return downloadBaseUrl; }
    public void setDownloadBaseUrl(String downloadBaseUrl) { this.downloadBaseUrl = downloadBaseUrl; }

    public String getBinaryFilename() { return binaryFilename; }
    public void setBinaryFilename(String binaryFilename) { this.binaryFilename = binaryFilename; }

    public String getConfigRoot() { return configRoot; }
    public void setConfigRoot(String configRoot) { this.configRoot = configRoot; }

    public long getDefaultTimeoutMs() { return defaultTimeoutMs; }
    public void setDefaultTimeoutMs(long defaultTimeoutMs) { this.defaultTimeoutMs = defaultTimeoutMs; }

    public int getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(int maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; }
}
