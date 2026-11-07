package com.miaoshou.mcp.repository;

import com.miaoshou.mcp.domain.SnapshotRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SnapshotMapper {
    @Insert("""
            INSERT INTO ms_product_snapshot(id, detail_id, shop_id, site, oss_md5, original_json, created_at)
            VALUES(#{id}, #{detailId}, #{shopId}, #{site}, #{ossMd5}, #{originalJson}, #{createdAt})
            """)
    void insert(SnapshotRow row);

    @Select("""
            SELECT id, detail_id, shop_id, site, oss_md5, original_json, created_at
            FROM ms_product_snapshot WHERE id = #{id}
            """)
    SnapshotRow find(@Param("id") String id);
}
