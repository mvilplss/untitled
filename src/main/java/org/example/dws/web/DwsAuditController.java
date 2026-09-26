package org.example.dws.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.dws.DwsAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "钉钉 DWS · 审计",
        description = "审计日志查询（已脱敏）。按行返回，便于运维排查。")
@RestController
@RequestMapping("/api/dws/audit")
public class DwsAuditController {

    private final DwsAuditService audit;

    public DwsAuditController(DwsAuditService audit) {
        this.audit = audit;
    }

    @Operation(summary = "查询最近审计日志")
    @GetMapping
    public Map<String, Object> tail(
            @Parameter(description = "最多返回行数（默认 50，最大 500）")
            @RequestParam(required = false, defaultValue = "50") int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limit", n);
        body.put("file", audit.auditFile().toString());
        body.put("content", audit.tail(n));
        return body;
    }
}
