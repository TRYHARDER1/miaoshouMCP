package com.miaoshou.mcp.repository;

import com.miaoshou.mcp.domain.image.processed.MuseProcessedModels.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Independent state/events. All mutations require the prior version and run in one short transaction. */
@Mapper
public interface MuseProcessedResultMapper {
    @Update("UPDATE ms_muse_processed_result SET updated_at=updated_at WHERE 0") void lock();
    @Select("SELECT * FROM ms_muse_processed_result WHERE job_id=#{job} AND asset_id=#{asset}")
    Row find(@Param("job") String job,@Param("asset") String asset);
    @Select("SELECT * FROM ms_muse_processed_result WHERE job_id=#{job} ORDER BY asset_id") List<Row> rows(String job);
    @Insert("""
        INSERT INTO ms_muse_processed_result(job_id,asset_id,source_url_hash,original_sha256,status,created_at,updated_at)
        VALUES(#{s.job.jobId},#{s.result.assetId},#{s.result.urlHash},#{s.original.sha256},'PENDING',#{now},#{now})
        ON CONFLICT(job_id,asset_id) DO NOTHING
        """) int insert(@Param("s") Source source,@Param("now") String now);
    @Update("""
        UPDATE ms_muse_processed_result SET status=#{next},row_version=row_version+1,attempt_no=attempt_no+1,
        retry_count=retry_count+#{retry},attempt_token=#{token},lease_until=#{lease},staging_relative_path=#{stage},
        error_code=NULL,failure_phase=NULL,download_started_at=CASE WHEN #{network}=1 THEN #{now} ELSE download_started_at END,
        downloaded_at=CASE WHEN #{network}=1 THEN NULL ELSE downloaded_at END,updated_at=#{now}
        WHERE job_id=#{r.jobId} AND asset_id=#{r.assetId} AND row_version=#{r.rowVersion}
        """) int claim(@Param("r") Row row,@Param("next") State next,@Param("token") String token,@Param("lease") String lease,
                          @Param("stage") String stage,@Param("retry") int retry,@Param("network") boolean network,@Param("now") String now);
    @Update("""
        UPDATE ms_muse_processed_result SET status='DOWNLOADED',row_version=row_version+1,
        staging_relative_path=#{stage},http_status=200,media_type=#{m.mediaType},actual_bytes=#{m.actualBytes},
        processed_sha256=#{m.sha256},downloaded_at=#{now},updated_at=#{now}
        WHERE job_id=#{r.jobId} AND asset_id=#{r.assetId} AND row_version=#{r.rowVersion} AND attempt_token=#{r.attemptToken}
        """) int downloaded(@Param("r") Row row,@Param("m") Metadata metadata,@Param("stage") String stage,@Param("now") String now);
    @Update("""
        UPDATE ms_muse_processed_result SET status='VALIDATING',row_version=row_version+1,
        staging_relative_path=#{stage},updated_at=#{now} WHERE job_id=#{r.jobId} AND asset_id=#{r.assetId}
        AND row_version=#{r.rowVersion} AND attempt_token=#{r.attemptToken}
        """) int validating(@Param("r") Row row,@Param("stage") String stage,@Param("now") String now);
    @Update("""
        UPDATE ms_muse_processed_result SET status='VALIDATED',row_version=row_version+1,lease_until=NULL,staging_relative_path=NULL,
        processed_relative_path=#{path},media_type=#{m.mediaType},actual_bytes=#{m.actualBytes},width=#{m.width},height=#{m.height},
        processed_sha256=#{m.sha256},validator_version=#{m.validatorVersion},validated_at=#{now},error_code=NULL,failure_phase=NULL,updated_at=#{now}
        WHERE job_id=#{r.jobId} AND asset_id=#{r.assetId} AND row_version=#{r.rowVersion} AND attempt_token=#{r.attemptToken}
        """) int validated(@Param("r") Row row,@Param("m") Metadata metadata,@Param("path") String path,@Param("now") String now);
    @Update("""
        UPDATE ms_muse_processed_result SET status='FAILED',row_version=row_version+1,lease_until=NULL,error_code=#{code},
        failure_phase=#{phase},http_status=COALESCE(#{http},http_status),updated_at=#{now}
        WHERE job_id=#{r.jobId} AND asset_id=#{r.assetId} AND row_version=#{r.rowVersion}
        """) int fail(@Param("r") Row row,@Param("code") String code,@Param("phase") String phase,@Param("http") Integer http,@Param("now") String now);
    @Update("""
        UPDATE ms_muse_processed_result SET row_version=row_version+1,lease_until=#{lease},updated_at=#{now}
        WHERE job_id=#{r.jobId} AND asset_id=#{r.assetId} AND row_version=#{r.rowVersion} AND attempt_token=#{r.attemptToken}
        """) int heartbeat(@Param("r") Row row,@Param("lease") String lease,@Param("now") String now);
    @Insert("""
        INSERT INTO ms_muse_processed_event(job_id,asset_id,row_version,attempt_no,from_status,to_status,event_code,error_code,created_at)
        VALUES(#{r.jobId},#{r.assetId},#{r.rowVersion},#{r.attemptNo},#{from},#{r.status},#{code},#{r.errorCode},#{now})
        """) void event(@Param("r") Row row,@Param("from") State from,@Param("code") String code,@Param("now") String now);
    @Select("SELECT * FROM ms_muse_processed_result WHERE status IN ('DOWNLOADING','DOWNLOADED','VALIDATING') AND lease_until<=#{now} ORDER BY lease_until LIMIT 100")
    List<Row> expired(String now);
    @Select("SELECT count(*) FROM ms_muse_processed_result WHERE status IN ('DOWNLOADING','DOWNLOADED','VALIDATING') AND lease_until>#{now}")
    int liveCount(String now);
    @Select("SELECT count(*) FROM ms_muse_processed_result WHERE status='VALIDATED' AND processed_sha256=#{sha} AND asset_id<>#{asset}")
    int duplicates(@Param("sha") String sha,@Param("asset") String asset);
}
