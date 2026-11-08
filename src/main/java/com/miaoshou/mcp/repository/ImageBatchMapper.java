package com.miaoshou.mcp.repository;

import com.miaoshou.mcp.domain.image.batch.BatchModels.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** SQLite allocation and state persistence; invoked under one scheduling transaction. */
@Mapper
public interface ImageBatchMapper {
    @Insert("""
        INSERT INTO ms_image_batch_plan(id,snapshot_id,batch_size,manifest_hash,total_assets,created_at,updated_at)
        VALUES(#{id},#{snapshotId},#{batchSize},#{manifestHash},#{totalAssets},#{createdAt},#{updatedAt})
        ON CONFLICT(snapshot_id) DO NOTHING
        """)
    int insertPlan(PlanRow row);

    @Select("SELECT * FROM ms_image_batch_plan WHERE snapshot_id=#{snapshotId}")
    PlanRow planBySnapshot(String snapshotId);

    @Select("SELECT * FROM ms_image_batch_plan WHERE id=#{id}")
    PlanRow plan(String id);

    @Insert("""
        INSERT INTO ms_image_batch(id,plan_id,sequence,status,created_at,updated_at)
        VALUES(#{id},#{planId},#{sequence},#{status},#{createdAt},#{updatedAt})
        """)
    void insertBatch(BatchRow row);

    @Insert("""
        INSERT INTO ms_image_batch_task(plan_id,batch_id,asset_id,sequence,status,retry_count,created_at,updated_at)
        VALUES(#{planId},#{batchId},#{assetId},#{sequence},#{status},#{retryCount},#{createdAt},#{updatedAt})
        """)
    void insertTask(TaskRow row);

    @Select("SELECT * FROM ms_image_batch WHERE id=#{id}")
    BatchRow batch(String id);

    @Select("SELECT * FROM ms_image_batch WHERE plan_id=#{planId} ORDER BY sequence")
    List<BatchRow> batches(String planId);

    @Select("SELECT * FROM ms_image_batch_task WHERE batch_id=#{batchId} ORDER BY sequence")
    List<TaskRow> tasks(String batchId);

    @Update("""
        UPDATE ms_image_batch_task SET status=#{status},error_code=#{errorCode},retry_count=#{retryCount},
        attempt_token=#{attemptToken},lease_until=#{leaseUntil},started_at=#{startedAt},
        completed_at=#{completedAt},updated_at=#{updatedAt} WHERE plan_id=#{planId} AND asset_id=#{assetId}
        """)
    void updateTask(TaskRow row);

    @Update("UPDATE ms_image_batch SET status=#{status},updated_at=#{updatedAt} WHERE id=#{id}")
    void updateBatch(BatchRow row);

    @Update("UPDATE ms_image_batch_plan SET updated_at=#{now} WHERE id=#{id}")
    void touchPlan(@Param("id") String id, @Param("now") String now);

    /** First statement of every transaction: acquire writer lock before any reads. */
    @Update("""
        UPDATE ms_image_batch_task SET status='FAILED',error_code='LEASE_EXPIRED',lease_until=NULL,
        completed_at=#{now},updated_at=#{now} WHERE status='PROCESSING' AND lease_until <= #{now}
        """)
    int expire(String now);

    @Update("""
        UPDATE ms_image_batch_plan SET updated_at=#{now} WHERE id IN
        (SELECT plan_id FROM ms_image_batch_task WHERE error_code='LEASE_EXPIRED' AND updated_at=#{now})
        """)
    void touchExpiredPlans(String now);

    @Select("SELECT * FROM ms_image_batch WHERE status NOT IN ('PAUSED','CANCELLED')")
    List<BatchRow> recoverableBatches();
}
