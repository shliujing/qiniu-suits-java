package com.qiniu.service.qoss;

import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager.*;
import com.qiniu.service.interfaces.ILineProcess;
import com.qiniu.storage.Configuration;
import com.qiniu.util.Auth;
import com.qiniu.util.HttpResponseUtils;
import com.qiniu.util.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MoveFile extends OperationBase implements ILineProcess<Map<String, String>>, Cloneable {

    final private String toBucket;
    final private String newKeyIndex;
    final private String keyPrefix;
    final private String rmPrefix;

    public MoveFile(Auth auth, Configuration configuration, String bucket, String toBucket, String newKeyIndex,
                    String keyPrefix, String rmPrefix, boolean forceIfOnlyPrefix, String resultPath, int resultIndex)
            throws IOException {
        // 目标 bucket 为空时规定为 rename 操作
        super(auth, configuration, bucket, toBucket == null || "".equals(toBucket) ? "rename" : "move",
                resultPath, resultIndex);
        if (newKeyIndex == null || "".equals(newKeyIndex)) {
            this.newKeyIndex = "key";
            if (toBucket == null || "".equals(toBucket)) {
                // rename 操作时未设置 new-key 的条件判断
                if (forceIfOnlyPrefix) {
                    if (keyPrefix == null || "".equals(keyPrefix))
                        throw new IOException("although prefix-force is true, but the add-prefix is empty.");
                } else {
                    throw new IOException("there is no newKey index, if you only want to add prefix for renaming, " +
                            "please set the \"prefix-force\" as true.");
                }
            }
        } else {
            this.newKeyIndex = newKeyIndex;
        }
        this.toBucket = "".equals(toBucket) ? null : toBucket;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.rmPrefix = rmPrefix == null ? "" : rmPrefix;
    }

    public MoveFile(Auth auth, Configuration configuration, String bucket, String toBucket, String newKeyIndex,
                    String keyPrefix, String rmPrefix, boolean forceIfOnlyPrefix, String resultPath) throws IOException {
        this(auth, configuration, bucket, toBucket, newKeyIndex, keyPrefix, rmPrefix, forceIfOnlyPrefix,
                resultPath, 0);
    }

    private String formatKey(String key) {
        return keyPrefix + key.substring(0, rmPrefix.length()).replace(rmPrefix, "")
                + key.substring(rmPrefix.length());
    }

    public String processLine(Map<String, String> line) throws QiniuException {
        if (StringUtils.isNullOrEmpty(line.get(newKeyIndex))) {
            errorLineList.add(String.valueOf(line) + "\tno target " + newKeyIndex + " in the line map.");
            throw new QiniuException(null, "\tno target " + newKeyIndex + " in the line map.");
        }
        Response response;
        if (toBucket == null || "".equals(toBucket))
            response = bucketManager.rename(bucket, line.get("key"), formatKey(line.get(newKeyIndex)), false);
        else
            response = bucketManager.move(bucket, line.get("key"), toBucket, formatKey(line.get(newKeyIndex)), false);
        return "{\"code\":" + response.statusCode + ",\"message\":\"" + HttpResponseUtils.getResult(response) + "\"}";
    }

    synchronized public BatchOperations getOperations(List<Map<String, String>> lineList) {
        lineList.forEach(line -> {
            if (StringUtils.isNullOrEmpty(line.get("key")) || StringUtils.isNullOrEmpty(line.get(newKeyIndex)))
                errorLineList.add(String.valueOf(line) + "\tno target key in the line map.");
            else {
                if (toBucket == null || "".equals(toBucket))
                    batchOperations.addRenameOp(bucket, line.get("key"), formatKey(line.get(newKeyIndex)));
                else
                    batchOperations.addMoveOp(bucket, line.get("key"), toBucket, formatKey(line.get(newKeyIndex)));
            }
        });
        return batchOperations;
    }

    public String getInputParams(Map<String, String> line) {
        return line.get("key") + "\t" + line.get(newKeyIndex);
    }
}
