package org.example;

import org.example.dws.DwsBinaryDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /**
     * Spring Boot 启动完成后异步触发 dws 二进制探测/下载。
     * 失败仅 WARN，不阻塞启动；后续 dws 调用再抛 {@code BinaryMissing}。
     */
    @Component
    public static class DwsBootstrap implements ApplicationRunner {

        private final DwsBinaryDownloader downloader;

        public DwsBootstrap(DwsBinaryDownloader downloader) {
            this.downloader = downloader;
        }

        @Override
        public void run(ApplicationArguments args) {
            try {
                downloader.ensurePresent();
            } catch (Exception e) {
                log.warn("dws binary bootstrap failed: {}", e.getMessage());
            }
        }
    }
}
