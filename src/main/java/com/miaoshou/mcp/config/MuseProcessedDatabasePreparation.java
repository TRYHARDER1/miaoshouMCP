package com.miaoshou.mcp.config;

import com.miaoshou.mcp.common.BusinessException;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.stereotype.Component;

/** Consistent SQLite backup before the normal schema initializer first adds the M5.4 tables. */
@Component
public class MuseProcessedDatabasePreparation implements BeanPostProcessor {
    private final ObjectProvider<DataSource> sources;
    public MuseProcessedDatabasePreparation(ObjectProvider<DataSource> sources) { this.sources=sources; }
    @Override public Object postProcessBeforeInitialization(Object bean,String name) {
        if(!(bean instanceof DataSourceScriptDatabaseInitializer)) { return bean; }
        try(var connection=sources.getObject().getConnection()) {
            if(!connection.getMetaData().getURL().startsWith("jdbc:sqlite:")) { return bean; }
            boolean old=exists(connection,"ms_product_snapshot"),state=exists(connection,"ms_muse_processed_result"),events=exists(connection,"ms_muse_processed_event");
            if(state!=events) { throw new BusinessException("M54_SCHEMA_INCOMPLETE","Incomplete processed schema requires explicit recovery"); }
            if(old && !state) {
                String file=null;try(var s=connection.createStatement();var rows=s.executeQuery("PRAGMA database_list")) {
                    while(rows.next()) { if("main".equals(rows.getString("name"))) { file=rows.getString("file"); } }
                }
                if(file!=null && !file.isBlank()) {
                    Path original=Path.of(file).toAbsolutePath().normalize();Path directory=original.getParent().resolve(".backups");
                    if(Files.isSymbolicLink(directory)) { throw new IllegalStateException(); }
                    Files.createDirectories(directory);
                    if(!directory.toRealPath().startsWith(original.getParent().toRealPath())) { throw new IllegalStateException(); }
                    Path target=directory.resolve(original.getFileName()+".pre-m54-"+UUID.randomUUID()+".db");
                    try(var statement=connection.prepareStatement("VACUUM INTO ?")) { statement.setString(1,target.toString());statement.execute(); }
                }
            }
            return bean;
        } catch(BusinessException e) { throw e; }
        catch(Exception e) { throw new BusinessException("M54_BACKUP_FAILED","Pre-upgrade SQLite backup failed; initialization stopped"); }
    }
    private boolean exists(Connection c,String table) throws SQLException {
        try(var s=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            s.setString(1,table);try(var r=s.executeQuery()) { return r.next(); }
        }
    }
}
