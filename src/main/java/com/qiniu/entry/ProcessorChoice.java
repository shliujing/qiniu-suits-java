package com.qiniu.entry;

import com.qiniu.common.Zone;
import com.qiniu.model.parameter.*;
import com.qiniu.service.interfaces.IEntryParam;
import com.qiniu.service.interfaces.ILineProcess;
import com.qiniu.service.media.QiniuPfop;
import com.qiniu.service.media.QueryAvinfo;
import com.qiniu.service.media.QueryPfopResult;
import com.qiniu.service.process.FileFilter;
import com.qiniu.service.process.FileInfoFilterProcess;
import com.qiniu.service.qoss.*;
import com.qiniu.storage.Configuration;
import com.qiniu.util.Auth;

import java.io.IOException;
import java.util.Map;

public class ProcessorChoice {

    private IEntryParam entryParam;
    private String process;
    private int retryCount;
    private String resultPath;
    private String resultFormat;
    private String resultSeparator;
    private Configuration configuration = new Configuration(Zone.autoZone());

    public ProcessorChoice(IEntryParam entryParam) throws IOException {
        this.entryParam = entryParam;
        CommonParams commonParams = new CommonParams(entryParam);
        process = commonParams.getProcess();
        retryCount = commonParams.getRetryCount();
        resultPath = commonParams.getResultPath();
        resultFormat = commonParams.getResultFormat();
        resultSeparator = commonParams.getResultSeparator();
    }

    public ILineProcess<Map<String, String>> getFileProcessor() throws Exception {

        ListFilterParams listFilterParams = new ListFilterParams(entryParam);
        FileFilter fileFilter = new FileFilter();
        fileFilter.setKeyConditions(listFilterParams.getKeyPrefix(), listFilterParams.getKeySuffix(),
                listFilterParams.getKeyRegex());
        fileFilter.setAntiKeyConditions(listFilterParams.getAntiKeyPrefix(), listFilterParams.getAntiKeySuffix(),
                listFilterParams.getAntiKeyRegex());
        fileFilter.setMimeConditions(listFilterParams.getMime(), listFilterParams.getAntiMime());
        fileFilter.setOtherConditions(listFilterParams.getPutTimeMax(), listFilterParams.getPutTimeMin(),
                listFilterParams.getType());
        ListFieldSaveParams fieldParams = new ListFieldSaveParams(entryParam);
        ILineProcess<Map<String, String>> processor;
        ILineProcess<Map<String, String>> nextProcessor = whichNextProcessor();
        if (fileFilter.isValid()) {
            processor = new FileInfoFilterProcess(resultPath, resultFormat, resultSeparator, fileFilter,
                    fieldParams.getUsedFields());
            if (process != null && !"".equals(process) && !"filter".equals(process)) {
                processor.setNextProcessor(nextProcessor);
            }
        } else {
            if ("filter".equals(process)) {
                throw new Exception("please set the correct filter conditions.");
            } else {
                processor = nextProcessor;
            }
        }
        if (processor != null) processor.setRetryCount(retryCount);
        return processor;
    }

    private ILineProcess<Map<String, String>> whichNextProcessor() throws Exception {
        ILineProcess<Map<String, String>> processor = null;
        switch (process) {
            case "status": {
                FileStatusParams fileStatusParams = new FileStatusParams(entryParam);
                String ak = fileStatusParams.getProcessAk();
                String sk = fileStatusParams.getProcessSk();
                processor = new ChangeStatus(Auth.create(ak, sk), configuration, fileStatusParams.getBucket(),
                        fileStatusParams.getTargetStatus(), resultPath);
                break;
            }
            case "type": {
                FileTypeParams fileTypeParams = new FileTypeParams(entryParam);
                String ak = fileTypeParams.getProcessAk();
                String sk = fileTypeParams.getProcessSk();
                processor = new ChangeType(Auth.create(ak, sk), configuration, fileTypeParams.getBucket(),
                        fileTypeParams.getTargetType(), resultPath);
                break;
            }
            case "lifecycle": {
                LifecycleParams lifecycleParams = new LifecycleParams(entryParam);
                String ak = lifecycleParams.getProcessAk();
                String sk = lifecycleParams.getProcessSk();
                processor = new UpdateLifecycle(Auth.create(ak, sk), configuration, lifecycleParams.getBucket(),
                        lifecycleParams.getDays(), resultPath);
                break;
            }
            case "copy": {
                FileCopyParams fileCopyParams = new FileCopyParams(entryParam);
                String ak = fileCopyParams.getProcessAk();
                String sk = fileCopyParams.getProcessSk();
                processor = new CopyFile(Auth.create(ak, sk), configuration, fileCopyParams.getBucket(),
                        fileCopyParams.getTargetBucket(), fileCopyParams.getKeepKey(), fileCopyParams.getKeyPrefix(),
                        resultPath);
                break;
            }
            case "move":
            case "rename": {
                FileMoveParams fileMoveParams = new FileMoveParams(entryParam);
                String ak = fileMoveParams.getProcessAk();
                String sk = fileMoveParams.getProcessSk();
                processor = new MoveFile(Auth.create(ak, sk), configuration, fileMoveParams.getBucket(),
                        fileMoveParams.getTargetBucket(), fileMoveParams.getKeyPrefix(), resultPath);
                break;
            }
            case "delete": {
                QossParams qossParams = new QossParams(entryParam);
                String ak = qossParams.getProcessAk();
                String sk = qossParams.getProcessSk();
                processor = new DeleteFile(Auth.create(ak, sk), configuration, qossParams.getBucket(), resultPath);
                break;
            }
            case "asyncfetch": {
                AsyncFetchParams asyncFetchParams = new AsyncFetchParams(entryParam);
                String ak = asyncFetchParams.getProcessAk();
                String sk = asyncFetchParams.getProcessSk();
                Auth auth = (asyncFetchParams.getNeedSign()) ? Auth.create(ak, sk) : null;
                processor = new AsyncFetch(Auth.create(ak, sk), configuration, asyncFetchParams.getTargetBucket(),
                        asyncFetchParams.getDomain(), asyncFetchParams.getProtocol(), auth, asyncFetchParams.getKeepKey(),
                        asyncFetchParams.getKeyPrefix(), asyncFetchParams.getHashCheck(), resultPath);
                if (asyncFetchParams.hasCustomArgs())
                    ((AsyncFetch) processor).setFetchArgs(asyncFetchParams.getHost(), asyncFetchParams.getCallbackUrl(),
                            asyncFetchParams.getCallbackBody(), asyncFetchParams.getCallbackBodyType(),
                            asyncFetchParams.getCallbackHost(), asyncFetchParams.getFileType(),
                            asyncFetchParams.getIgnoreSameKey());
                break;
            }
            case "avinfo": {
                AvinfoParams avinfoParams = new AvinfoParams(entryParam);
                Auth auth = null;
                if (avinfoParams.getNeedSign()) {
                    String ak = avinfoParams.getProcessAk();
                    String sk = avinfoParams.getProcessSk();
                    auth = Auth.create(ak, sk);
                }
                processor = new QueryAvinfo(avinfoParams.getDomain(), avinfoParams.getProtocol(), auth, resultPath);
                break;
            }
            case "pfop": {
                PfopParams pfopParams = new PfopParams(entryParam);
                String ak = pfopParams.getProcessAk();
                String sk = pfopParams.getProcessSk();
                processor = new QiniuPfop(Auth.create(ak, sk), configuration, pfopParams.getBucket(),
                        pfopParams.getPipeline(), resultPath);
                break;
            }
            case "pfopresult": {
                processor = new QueryPfopResult(resultPath);
                break;
            }
            case "qhash": {
                QhashParams qhashParams = new QhashParams(entryParam);
                Auth auth = null;
                if (qhashParams.getNeedSign()) {
                    String ak = qhashParams.getProcessAk();
                    String sk = qhashParams.getProcessSk();
                    auth = Auth.create(ak, sk);
                }
                processor = new QueryHash(qhashParams.getDomain(), qhashParams.getAlgorithm(), qhashParams.getProtocol(),
                        auth, qhashParams.getResultPath());
                break;
            }
            case "stat": {
                QossParams qossParams = new QossParams(entryParam);
                String ak = qossParams.getProcessAk();
                String sk = qossParams.getProcessSk();
                processor = new FileStat(Auth.create(ak, sk), configuration, qossParams.getBucket(),
                        qossParams.getResultPath());
                break;
            }
            case "privateurl": {
                PrivateUrlParams privateUrlParams = new PrivateUrlParams(entryParam);
                String ak = privateUrlParams.getProcessAk();
                String sk = privateUrlParams.getProcessSk();
                processor = new PrivateUrl(Auth.create(ak, sk), privateUrlParams.getDomain(),
                        privateUrlParams.getProtocol(), privateUrlParams.getExpires(), privateUrlParams.getResultPath());
                break;
            }
        }
        if (processor != null) processor.setRetryCount(retryCount);
        return processor;
    }
}
