package com.miaoshou.mcp.repository;

import com.miaoshou.mcp.domain.DraftRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DraftMapper {
    @Insert("""
            INSERT INTO ms_product_draft(id, snapshot_id, kind, status, draft_json, created_at)
            VALUES(#{id}, #{snapshotId}, #{kind}, #{status}, #{draftJson}, #{createdAt})
            ON CONFLICT(snapshot_id, kind) DO NOTHING
            """)
    void insertIfAbsent(DraftRow row);

    @Select("""
            SELECT id, snapshot_id, kind, status, draft_json, created_at
            FROM ms_product_draft WHERE snapshot_id = #{snapshotId} AND kind = #{kind}
            """)
    DraftRow findBySnapshot(@Param("snapshotId") String snapshotId, @Param("kind") String kind);

    @Select("""
            SELECT id, snapshot_id, kind, status, draft_json, created_at
            FROM ms_product_draft WHERE id = #{id}
            """)
    DraftRow find(@Param("id") String id);
}
