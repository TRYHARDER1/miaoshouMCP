package com.miaoshou.mcp.config;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.processed.MuseProcessedModels.Row;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;

/** Fail explicitly on incompatible pre-existing tables instead of silently assuming CREATE IF NOT EXISTS migrated them. */
@Component
public class MuseProcessedSchemaVerifier implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public MuseProcessedSchemaVerifier(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public void run(ApplicationArguments args) {
        Set<String> expected=Arrays.stream(Row.class.getRecordComponents()).map(c -> c.getName().replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> actual=new HashSet<>(jdbc.queryForList("SELECT name FROM pragma_table_info('ms_muse_processed_result')",String.class));
        if(!actual.equals(expected)) { throw new BusinessException("M54_SCHEMA_INVALID","Processed result schema differs from this version"); }
        Set<String> events=new HashSet<>(jdbc.queryForList("SELECT name FROM pragma_table_info('ms_muse_processed_event')",String.class));
        if(!events.equals(Set.of("event_id","job_id","asset_id","row_version","attempt_no","from_status","to_status","event_code","error_code","created_at"))) {
            throw new BusinessException("M54_SCHEMA_INVALID","Processed event schema differs from this version");
        }
        try(var input=new ClassPathResource("schema.sql").getInputStream()) {
            String schema=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replaceAll("--[^\\r\\n]*","");
            var pattern=java.util.regex.Pattern.compile("CREATE (TABLE|INDEX) IF NOT EXISTS (ms_muse_processed_(?:result|event)|idx_processed_[a-z_]+)\\s*.*?;",java.util.regex.Pattern.DOTALL);
            var match=pattern.matcher(schema);int count=0;
            while(match.find()) {
                String actualSql=jdbc.queryForObject("SELECT sql FROM sqlite_master WHERE type=? AND name=?",String.class,match.group(1).toLowerCase(Locale.ROOT),match.group(2));
                if(!normalize(match.group()).equals(normalize(actualSql))) { throw new IllegalStateException(); }count++;
            }
            if(count!=6) { throw new IllegalStateException(); }
        } catch(Exception e) { throw new BusinessException("M54_SCHEMA_INVALID","Processed constraints or indexes differ from this version"); }
    }
    private String normalize(String value) { return value.replaceAll("--[^\\r\\n]*","").replace("IF NOT EXISTS","").replaceAll("\\s+","").replace(";",""); }
}
