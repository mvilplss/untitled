package org.example.task.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.task.LastRunSummary;
import org.example.task.ScheduledTask;
import org.example.task.typehandler.SkillsTypeHandler;

import java.util.List;

/**
 * ac_scheduled_task 表 Mapper（替代原 ScheduledTaskRepository）。
 * <p>策略：简单 CRUD 用注解；聚合查询 {@link #lastRunAtByTaskId()} 走 XML。
 * <p>DB 列 snake_case，实体 camelCase；由 {@code mybatis.configuration.map-underscore-to-camel-case} 自动映射。
 * 仅 {@code skills} 列需要显式绑定 {@link SkillsTypeHandler}（List&lt;String&gt; ⇄ JSON 串）。
 */
@Mapper
public interface AcScheduledTaskMapper {

    @Insert("INSERT INTO ac_scheduled_task " +
            "(id, name, agent_id, prompt, skills, cron, enabled, created_at, updated_at) " +
            "VALUES (#{id}, #{name}, #{agentId}, #{prompt}, " +
            "#{skills, typeHandler=org.example.task.typehandler.SkillsTypeHandler}, " +
            "#{cron}, #{enabled}, #{createdAt}, #{updatedAt})")
    int insert(ScheduledTask task);

    @Update("UPDATE ac_scheduled_task SET " +
            "name=#{name}, agent_id=#{agentId}, prompt=#{prompt}, " +
            "skills=#{skills, typeHandler=org.example.task.typehandler.SkillsTypeHandler}, " +
            "cron=#{cron}, enabled=#{enabled}, updated_at=#{updatedAt} " +
            "WHERE id=#{id}")
    int update(ScheduledTask task);

    @Delete("DELETE FROM ac_scheduled_task WHERE id=#{id}")
    int delete(@Param("id") String id);

    @Select("SELECT id, name, agent_id, prompt, skills, cron, enabled, created_at, updated_at " +
            "FROM ac_scheduled_task WHERE id=#{id}")
    @Results({
            @Result(property = "skills", column = "skills", javaType = List.class,
                    typeHandler = SkillsTypeHandler.class)
    })
    ScheduledTask findById(@Param("id") String id);

    @Select("SELECT id, name, agent_id, prompt, skills, cron, enabled, created_at, updated_at " +
            "FROM ac_scheduled_task")
    @Results({
            @Result(property = "skills", column = "skills", javaType = List.class,
                    typeHandler = SkillsTypeHandler.class)
    })
    List<ScheduledTask> findAll();

    /**
     * 聚合每个任务的最近一次 run 起始时间（毫秒）。
     * XML 见 {@code resources/mapper/AcScheduledTaskMapper.xml}。
     */
    List<LastRunSummary> lastRunAtByTaskId();
}