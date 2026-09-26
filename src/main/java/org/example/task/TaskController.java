package org.example.task;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.example.task.mapper.AcTaskRunMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/tasks")
@Tag(name = "定时任务", description = "数字人的定时执行编排：选 Agent + 提示词 + skill 白名单 + cron 计划。Quartz 作触发引擎，同一任务不重叠；执行记录独立存储。")
public class TaskController {

    private final TaskSchedulerService scheduler;
    private final AcTaskRunMapper runMapper;

    public TaskController(TaskSchedulerService scheduler, AcTaskRunMapper runMapper) {
        this.scheduler = scheduler;
        this.runMapper = runMapper;
    }

    @Operation(summary = "列出所有定时任务（含 lastRunAt/nextRunAt/agentName）")
    @GetMapping
    public List<ScheduledTask> list() {
        return scheduler.list();
    }

    @Operation(summary = "按 ID 查询定时任务")
    @GetMapping("/{id}")
    public ScheduledTask get(@PathVariable @Pattern(regexp = "^[a-zA-Z0-9_-]{1,64}$") String id) {
        try {
            return scheduler.getById(id);
        } catch (NoSuchElementException e) {
            throw new org.example.web.NotFoundException(e.getMessage());
        }
    }

    @Operation(summary = "创建定时任务（注册 Quartz Job+Trigger）")
    @PostMapping
    public ResponseEntity<ScheduledTask> create(@Valid @RequestBody TaskUpsertRequest req) {
        ScheduledTask t = req.toTask();
        ScheduledTask created = scheduler.create(t);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "更新定时任务（cron/内容/启用标志）")
    @PutMapping("/{id}")
    public ScheduledTask update(@PathVariable @Pattern(regexp = "^[a-zA-Z0-9_-]{1,64}$") String id,
                                @Valid @RequestBody TaskUpsertRequest req) {
        req.id = id;
        ScheduledTask patch = req.toTask();
        return scheduler.update(id, patch);
    }

    @Operation(summary = "删除定时任务（删除 Quartz Job + runs 记录）")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Pattern(regexp = "^[a-zA-Z0-9_-]{1,64}$") String id) {
        scheduler.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "立即执行任务（异步；执行中返回 409）")
    @PostMapping("/{id}/run")
    public ResponseEntity<Map<String, String>> runNow(@PathVariable @Pattern(regexp = "^[a-zA-Z0-9_-]{1,64}$") String id) {
        try {
            String runId = scheduler.runNow(id);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", runId));
        } catch (NoSuchElementException e) {
            throw new org.example.web.NotFoundException(e.getMessage());
        }
    }

    @Operation(summary = "查看任务的执行记录（按 startedAt 倒序）")
    @GetMapping("/{id}/runs")
    public List<TaskRun> runs(@PathVariable @Pattern(regexp = "^[a-zA-Z0-9_-]{1,64}$") String id,
                              @RequestParam(defaultValue = "100") int limit) {
        try {
            scheduler.getById(id); // 校验任务存在
        } catch (NoSuchElementException e) {
            throw new org.example.web.NotFoundException(e.getMessage());
        }
        if (limit <= 0 || limit > 500) limit = 100;
        return runMapper.findByTaskId(id, limit);
    }

    /**
     * 任务入参：name/agentId/prompt/skills/cron/enabled。
     * id 必填（create/update 都用 body.id）；路径更新时 path id 覆盖 body.id（见 controller）。
     */
    public static class TaskUpsertRequest {
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9_-]{1,64}$")
        public String id;

        @NotBlank
        public String name;

        @NotBlank
        public String agentId;

        @NotBlank
        public String prompt;

        public List<String> skills;

        @NotBlank
        public String cron;

        public Boolean enabled;

        ScheduledTask toTask() {
            ScheduledTask t = new ScheduledTask();
            t.setId(id);
            t.setName(name);
            t.setAgentId(agentId);
            t.setPrompt(prompt);
            t.setSkills(skills == null || skills.isEmpty() ? null : skills);
            t.setCron(cron);
            t.setEnabled(enabled == null ? true : enabled);
            return t;
        }
    }
}