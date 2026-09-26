package org.example.dws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 维护 {@code <configRoot>/_registry.json}：本地 userId → 已绑定 DWS 账号（corpId / userId / corpName）。
 *
 * <p>持久化采用 tmp + ATOMIC_MOVE（沿用 {@code AgentPersistence} 风格）。
 */
@Component
public class DwsUserRegistry {

    private static final Logger log = LoggerFactory.getLogger(DwsUserRegistry.class);

    private final DwsUserDirectory directory;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    public DwsUserRegistry(DwsUserDirectory directory) {
        this.directory = directory;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(directory.registryFile().getParent());
        } catch (IOException e) {
            log.warn("Cannot create dws registry dir: {}", e.getMessage());
        }
    }

    public Map<String, RegistryEntry> loadAll() {
        synchronized (lock) {
            var f = directory.registryFile();
            if (!Files.exists(f)) return new LinkedHashMap<>();
            try {
                Map<String, RegistryEntry> map = mapper.readValue(
                        f.toFile(),
                        mapper.getTypeFactory().constructMapType(
                                LinkedHashMap.class, String.class, RegistryEntry.class));
                return map == null ? new LinkedHashMap<>() : map;
            } catch (Exception e) {
                log.error("Failed to parse dws registry {}: {}", f, e.getMessage());
                return new LinkedHashMap<>();
            }
        }
    }

    public Optional<RegistryEntry> find(String userId) {
        return Optional.ofNullable(loadAll().get(userId));
    }

    public void upsert(String userId, RegistryEntry entry) throws IOException {
        synchronized (lock) {
            Map<String, RegistryEntry> map = loadAll();
            map.put(userId, entry);
            persistLocked(map);
        }
    }

    public void remove(String userId) throws IOException {
        synchronized (lock) {
            Map<String, RegistryEntry> map = loadAll();
            if (map.remove(userId) != null) {
                persistLocked(map);
            }
        }
    }

    private void persistLocked(Map<String, RegistryEntry> map) throws IOException {
        var f = directory.registryFile();
        var tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, mapper.writeValueAsString(map));
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 用户名 + DWS corpId:userId 绑定记录。前端可见，敏感字段都已脱敏。
     */
    public static class RegistryEntry {
        public String userId;
        public String corpId;
        public String corpName;
        public String displayName;
        public Long lastLoginAt;
        public String status;
    }
}
