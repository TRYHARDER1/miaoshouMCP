package com.miaoshou.mcp.repository;

import com.miaoshou.mcp.domain.image.muse.MuseModels.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** M5.3 persistence only; never updates M5.2 allocation or execution state. */
@Mapper
public interface MuseJobMapper {
    @Update("UPDATE ms_muse_job SET updated_at=updated_at WHERE 0")
    void lock();
    @Select("SELECT * FROM ms_muse_job WHERE batch_id=#{batchId}")
    JobRow find(String batchId);
    @Insert("""
        INSERT INTO ms_muse_job(job_id,batch_id,version,status,context_json,package_json,build_token,lease_until,error_code,created_at,updated_at)
        VALUES(#{jobId},#{batchId},#{version},#{status},#{contextJson},#{packageJson},#{buildToken},#{leaseUntil},#{errorCode},#{createdAt},#{updatedAt})
        """)
    void insert(JobRow row);
    @Update("""
        UPDATE ms_muse_job SET status=#{status},package_json=#{packageJson},build_token=#{buildToken},
        lease_until=#{leaseUntil},error_code=#{errorCode},updated_at=#{updatedAt} WHERE job_id=#{jobId}
        """)
    void update(JobRow row);
    @Select("SELECT * FROM ms_muse_result WHERE job_id=#{jobId} ORDER BY asset_id")
    List<ResultRow> results(String jobId);
    @Select("SELECT * FROM ms_muse_result WHERE asset_id=#{assetId}")
    ResultRow byAsset(String assetId);
    @Select("SELECT * FROM ms_muse_result WHERE url_hash=#{urlHash}")
    ResultRow byUrl(String urlHash);
    @Insert("""
        INSERT INTO ms_muse_result(job_id,asset_id,download_url,url_hash,registered_at,status,download_verified)
        VALUES(#{jobId},#{assetId},#{downloadUrl},#{urlHash},#{registeredAt},#{status},#{downloadVerified})
        """)
    void insertResult(ResultRow row);
}
