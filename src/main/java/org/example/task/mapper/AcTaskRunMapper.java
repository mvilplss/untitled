package org.example.task.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.task.TaskRun;

import java.util.List;

/**
 * ac_task_run 表 Mapper（替代原 TaskRunRepository）。
 * <p>策略：简单 CRUD 用注解；多语句 {@link #trim} 走 XML（避开 MySQL 同表 DELETE+SELECT 限制）。
 */
@Mapper
public interface AcTaskRunMapper {

    @Insert("INSERT INTO ac_task_run " +
            "(run_id, task_id, trigger_type, status, started_at, finished_at, " +
            " duration_ms, session_id, reply_preview, error, " +
            " tokens_input, tokens_output, tokens_cached, tokens_total) " +
            "VALUES (#{runId}, #{taskId}, #{triggerType}, #{status}, #{startedAt}, #{finishedAt}, " +
            "#{durationMs}, #{sessionId}, #{replyPreview}, #{error}, " +
            "#{tokensInput}, #{tokensOutput}, #{tokensCached}, #{tokensTotal})")
    int insert(TaskRun r);

    /** QUEUED → RUNNING：手动执行路径下 task_run.run_id 已先插一行 QUEUED，此处只更新状态字段。 */
    @Update("UPDATE ac_task_run SET status=#{status}, started_at=#{startedAt}, session_id=#{sessionId} " +
            "WHERE run_id=#{runId}")
    int markRunning(TaskRun r);

    /** 完成态更新：finishedAt / durationMs / status / replyPreview / tokens / error。 */
    @Update("UPDATE ac_task_run SET status=#{status}, finished_at=#{finishedAt}, duration_ms=#{durationMs}, " +
            "reply_preview=#{replyPreview}, error=#{error}, " +
            "tokens_input=#{tokensInput}, tokens_output=#{tokensOutput}, " +
            "tokens_cached=#{tokensCached}, tokens_total=#{tokensTotal} " +
            "WHERE run_id=#{runId}")
    int finish(TaskRun r);

    @Select("SELECT run_id, task_id, trigger_type, status, started_at, finished_at, " +
            "duration_ms, session_id, reply_preview, error, " +
            "tokens_input, tokens_output, tokens_cached, tokens_total " +
            "FROM ac_task_run WHERE task_id=#{taskId} ORDER BY started_at DESC LIMIT #{limit}")
    List<TaskRun> findByTaskId(@Param("taskId") String taskId, @Param("limit") int limit);

    @Delete("DELETE FROM ac_task_run WHERE task_id=#{taskId}")
    int deleteByTaskId(@Param("taskId") String taskId);

    /**
     * 控制噪音：单任务保留最多 maxKeep 条；超过时按 startedAt 升序裁掉最旧的。
     * 返回被删除数量。XML 见 {@code resources/mapper/AcTaskRunMapper.xml}（用嵌套子查询绕开 MySQL 同表限制）。
     */
    int trim(@Param("taskId") String taskId, @Param("maxKeep") int maxKeep);
}