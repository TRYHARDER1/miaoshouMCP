package com.miaoshou.mcp.repository;

import com.miaoshou.mcp.domain.AiModels.GenerationRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GenerationMapper {
    @Insert("""
            INSERT INTO ms_ai_generation(id, snapshot_id, task, provider, model, prompt_version,
              started_at, input_summary_json, validation_status, review_status)
            VALUES(#{id}, #{snapshotId}, #{task}, #{provider}, #{model}, #{promptVersion},
              #{startedAt}, #{inputSummaryJson}, #{validationStatus}, #{reviewStatus})
            """)
    void start(GenerationRecord row);

    @Update("""
            UPDATE ms_ai_generation SET model=#{model}, completed_at=#{completedAt}, output_json=#{outputJson},
              validation_status=#{validationStatus}, error_code=#{errorCode}
            WHERE id=#{id} AND validation_status='RUNNING'
            """)
    void complete(GenerationRecord row);

    @Select("""
            SELECT id, snapshot_id, task, provider, model, prompt_version, started_at, completed_at,
              input_summary_json, output_json, validation_status, review_status, error_code
            FROM ms_ai_generation WHERE id=#{id}
            """)
    GenerationRecord find(@Param("id") String id);
}
