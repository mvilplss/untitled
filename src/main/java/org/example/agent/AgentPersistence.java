package org.example.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 将 AgentSpec 列表持久化到 JSON 文件。
 * 写入采用 tmp + ATOMIC_MOVE 模式，避免崩溃时文件被截断。
 */
@Component
public class AgentPersistence {

    private static final Logger log = LoggerFactory.getLogger(AgentPersistence.class);

    private final Path path;
    private final ObjectMapper mapper;

    public AgentPersistence(AgentProperties props) {
        this.path = Paths.get(props.getPersistence().getPath()).toAbsolutePath().normalize();
        // 持久化路径走字段不走 getter：spec.getDingtalk() 是 masked 版，磁盘要保存原值
        this.mapper = new ObjectMapper()
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
                .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            log.warn("Cannot create parent dir for {}: {}", path, e.getMessage());
        }
    }

    public Path getPath() {
        return path;
    }

    /** 读取所有持久化的 AgentSpec。文件不存在或损坏时返回空列表，不抛异常。 */
    public List<AgentSpec> load() {
        if (!Files.exists(path)) {
            log.info("Persistence file {} not found, starting with empty registry", path);
            return Collections.emptyList();
        }
        try {
            PersistFile pf = mapper.readValue(path.toFile(), PersistFile.class);
            if (pf == null || pf.agents == null) return Collections.emptyList();
            log.info("Loaded {} agents from {}", pf.agents.size(), path);
            return pf.agents;
        } catch (Exception e) {
            log.error("Failed to parse persistence file {}: {}", path, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 全量写入。原子（tmp + rename）。失败抛异常由调用方处理。 */
    public synchronized void save(List<AgentSpec> specs) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);

        PersistFile pf = new PersistFile();
        pf.agents = new ArrayList<>(specs);
        byte[] json = mapper.writeValueAsBytes(pf);

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, json);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 文件外层结构，含版本号便于将来迁移 */
    public static class PersistFile {
        public int version = 1;
        public List<AgentSpec> agents;
    }
}