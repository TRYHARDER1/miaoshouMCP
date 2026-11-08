package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.config.MuseProcessedSchemaVerifier;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.ai.mcp.server.enabled=false")
class MuseProcessedMigrationTest {
    static final Path ROOT=root();
    static final String DATABASE=prepare();
    static Path root() { try { return Files.createTempDirectory("miaoshou-upgrade-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    static String prepare() {
        String url="jdbc:sqlite:"+ROOT.resolve("legacy.db");
        try(var input=new ClassPathResource("schema.sql").getInputStream();var c=DriverManager.getConnection(url)) {
            String schema=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            ScriptUtils.executeSqlScript(c,new ByteArrayResource(schema.substring(0,schema.indexOf("-- M5.3")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            try(var s=c.prepareStatement("INSERT INTO ms_product_snapshot VALUES('old',17,23,'SG','baseline',?,'2026-11-08T00:00:00Z')")) {
                s.setString(1,Fixtures.envelope().toString());s.executeUpdate();
            }
            return url;
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",() -> DATABASE);r.add("muse.jobs-root",() -> ROOT.resolve("jobs").toString());r.add("muse.delivery-root",() -> ROOT.resolve("deliveries").toString());
    }
    @Autowired JdbcTemplate jdbc;
    @Test void consistentBackupPrecedesAdditiveUpgradeEvenFromBeforeM53() throws Exception {
        List<Path> backups;try(var files=Files.list(ROOT.resolve(".backups"))) { backups=files.toList(); }
        assertThat(backups).hasSize(1);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+backups.getFirst().toUri()+"?mode=ro");var s=c.createStatement()) {
            try(var rows=s.executeQuery("SELECT original_json FROM ms_product_snapshot WHERE id='old'")) { assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo(Fixtures.envelope().toString()); }
            try(var rows=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('ms_muse_result','ms_muse_processed_result')")) { assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero(); }
        }
        assertThat(jdbc.queryForObject("SELECT original_json FROM ms_product_snapshot WHERE id='old'",String.class)).isEqualTo(Fixtures.envelope().toString());
        assertThat(jdbc.queryForObject("SELECT sql FROM sqlite_master WHERE name='ms_muse_result'",String.class)).contains("CHECK(download_verified=0)");
    }
    @Test void schemaVerifierDetectsMissingSafetyIndex() {
        jdbc.execute("DROP INDEX idx_processed_content_sha");
        try { assertThatThrownBy(() -> new MuseProcessedSchemaVerifier(jdbc).run(null)).isInstanceOf(BusinessException.class); }
        finally { jdbc.execute("CREATE INDEX IF NOT EXISTS idx_processed_content_sha ON ms_muse_processed_result(processed_sha256)"); }
    }
    @Test void interruptedDdlRollsBackBothNewTables() throws Exception {
        Path interrupted=ROOT.resolve("interrupted.db");String sql;
        try(var input=new ClassPathResource("schema.sql").getInputStream()) {
            sql=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        String section=sql.substring(sql.indexOf("-- M5.4 independent"));
        String invalid=section.replace("CREATE TABLE IF NOT EXISTS ms_muse_processed_event (","CREATE TABLE INVALID SQL (");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+interrupted)) {
            try(var statement=c.createStatement()) { statement.execute("CREATE TABLE ms_muse_result(job_id TEXT,asset_id TEXT,PRIMARY KEY(job_id,asset_id))"); }
            assertThatThrownBy(() -> ScriptUtils.executeSqlScript(c,new ByteArrayResource(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    .isInstanceOf(org.springframework.jdbc.datasource.init.ScriptException.class);
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+interrupted);var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name LIKE 'ms_muse_processed_%'")) {
            assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isZero();
        }
    }
}
