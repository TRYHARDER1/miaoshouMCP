package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.muse.MuseDeliveryModels.View;
import com.miaoshou.mcp.repository.MuseJobMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** An additive safe projection; historical jobs, prompts and mappings remain untouched. */
@Service
public class MuseDeliveryService {
    private final MuseJobMapper store;private final MuseBatchReader reader;private final MusePackageFiles packages;
    private final MuseDeliverySanitizer sanitizer;private final MuseDeliveryPromptBuilder prompts;private final MuseDeliveryFiles files;
    private final TransactionTemplate tx;
    public MuseDeliveryService(MuseJobMapper store,MuseBatchReader reader,MusePackageFiles packages,MuseDeliverySanitizer sanitizer,
                               MuseDeliveryPromptBuilder prompts,MuseDeliveryFiles files,PlatformTransactionManager manager) {
        this.store=store;this.reader=reader;this.packages=packages;this.sanitizer=sanitizer;this.prompts=prompts;this.files=files;tx=new TransactionTemplate(manager);
    }
    /** Copy only whitelisted metadata and validated local originals; never downloads or creates a job. */
    public View export(String batchId) {
        try {
            return tx.execute(s -> {
                store.lock();reader.read(batchId,true);var row=store.find(batchId);
                if(row==null || row.packageJson()==null) { throw new BusinessException("MUSE_JOB_NOT_EXPORTED","An existing published job is required"); }
                var original=packages.inspect(row);if(original==null) { throw new BusinessException("MUSE_PACKAGE_CORRUPT","Original package missing"); }
                var manifest=sanitizer.sanitize(original);var path=files.export(manifest,original,packages.directory(row));
                return new View(manifest,prompts.build(manifest),path.toString());
            });
        } catch(DataAccessException | TransactionException e) { throw new BusinessException("MUSE_STORAGE_FAILED","Delivery transaction failed"); }
    }
}
