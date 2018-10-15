package com.qiniu.service.impl;

import com.google.gson.*;
import com.qiniu.common.*;
import com.qiniu.http.Response;
import com.qiniu.interfaces.IBucketProcess;
import com.qiniu.interfaces.IOssFileProcess;
import com.qiniu.service.oss.ListBucket;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.storage.model.FileListing;
import com.qiniu.util.JsonConvertUtils;
import com.qiniu.util.StringUtils;
import com.qiniu.util.UrlSafeBase64;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ListBucketProcess implements IBucketProcess {

    private QiniuAuth auth;
    private Configuration configuration;
    private String bucket;
    private String resultFileDir;
    private FileReaderAndWriterMap fileReaderAndWriterMap = new FileReaderAndWriterMap();

    public ListBucketProcess(QiniuAuth auth, Configuration configuration, String bucket, String resultFileDir)
            throws IOException {

        this.auth = auth;
        this.configuration = configuration;
        this.bucket = bucket;
        this.resultFileDir = resultFileDir;
        fileReaderAndWriterMap.initWriter(resultFileDir, "list");
    }

    public String[] getFirstFileInfoAndMarker(Response response, String line, FileInfo fileInfo, int version) {

        String key = "";
        String info = "";
        String marker = "";
        int type;
        try {
            if (version == 1) {
                if (response != null || fileInfo != null) {
                    if (response != null) {
                        FileListing fileListing = response.jsonToObject(FileListing.class);
                        fileInfo = fileListing.items != null && fileListing.items.length > 0 ? fileListing.items[0] : null;
                    }
                    if (fileInfo != null) {
                        key = fileInfo.key;
                        type = fileInfo.type;
                        info = JsonConvertUtils.toJson(fileInfo);
                        marker = UrlSafeBase64.encodeToString("{\"c\":" + type + ",\"k\":\"" + key + "\"}");;
                    }
                }
            } else if (version == 2) {
                if (response != null || !StringUtils.isNullOrEmpty(line)) {
                    if (response != null) {
                        List<String> lineList = Arrays.asList(response.bodyString().split("\n"));
                        line = lineList.size() > 0 ? lineList.get(0) : null;
                    }
                    if (!StringUtils.isNullOrEmpty(line)) {
                        JsonObject json = JsonConvertUtils.toJsonObject(line);
                        JsonElement item = json.get("item");
                        if (item != null && !(item instanceof JsonNull)) {
                            key = item.getAsJsonObject().get("key").getAsString();
                            type = item.getAsJsonObject().get("type").getAsInt();
                            info = JsonConvertUtils.toJson(item);
                            marker = UrlSafeBase64.encodeToString("{\"c\":" + type + ",\"k\":\"" + key + "\"}");
                        }
                        marker = StringUtils.isNullOrEmpty(marker) ? json.get("marker").getAsString() : marker;
                    }
                }
            }
        } catch (QiniuException e) {}

        return new String[]{key, info, marker};
    }

    private Map<String, String> listByPrefix(ListBucket listBucket, List<String> prefixList, int version, boolean doWrite,
                                             IOssFileProcess iOssFileProcessor) throws QiniuException {

        Queue<QiniuException> exceptionQueue = new ConcurrentLinkedQueue<>();
        Map<String, String[]> fileInfoAndMarkerMap = prefixList.parallelStream()
//                .filter(prefix -> !prefix.contains("|"))
                .map(prefix -> {
                        Response response = null;
                        String[] firstFileInfoAndMarker = null;
                        try {
                            response = listBucket.run(bucket, prefix, null, null, 1, 3, version);
                            firstFileInfoAndMarker = getFirstFileInfoAndMarker(response, null, null, version);
                        } catch (QiniuException e) {
                            fileReaderAndWriterMap.writeErrorOrNull(bucket + "\t" + prefix + "\t" + e.error());
                            if (e.code() > 400) exceptionQueue.add(e);
                        } finally { if (response != null) response.close(); }
                        return firstFileInfoAndMarker;
        }).filter(fileInfoAndMarker -> !(fileInfoAndMarker == null || StringUtils.isNullOrEmpty(fileInfoAndMarker[0])))
            .collect(Collectors.toMap(
                    fileInfoAndMarker -> fileInfoAndMarker[1],
                    fileInfoAndMarker -> new String[]{fileInfoAndMarker[0], fileInfoAndMarker[2]},
                    (oldValue, newValue) -> newValue
        ));

        if (doWrite) fileReaderAndWriterMap.writeSuccess(String.join("\n", fileInfoAndMarkerMap.keySet()));
        QiniuException qiniuException = exceptionQueue.poll();
        if (iOssFileProcessor != null) {
            if (qiniuException == null) {
                qiniuException = processFileInfo(fileInfoAndMarkerMap.keySet().parallelStream(), iOssFileProcessor,
                        false, 3, exceptionQueue).poll();
                if (qiniuException != null) throw qiniuException;
            } else throw qiniuException;
        }

        return fileInfoAndMarkerMap.values().parallelStream()
                .collect(Collectors.toMap(keyAndMarker -> keyAndMarker[0], keyAndMarker -> keyAndMarker[1]));
    }

    private List<String> getSecondFilePrefix(List<String> prefixList, Map<String, String> delimitedFileMap) {
        List<String> firstKeyList = new ArrayList<>(delimitedFileMap.keySet());
        List<String> secondPrefixList = new ArrayList<>();
        for (String firstKey : firstKeyList) {
            String firstPrefix = firstKey.substring(0, 1);
            if (StringUtils.isNullOrEmpty(delimitedFileMap.get(firstKey))) {
                secondPrefixList.add(firstPrefix);
                continue;
            }
            for (String secondPrefix : prefixList) {
                secondPrefixList.add(firstPrefix + secondPrefix);
            }
        }

        return secondPrefixList;
    }

    public Map<String, String> getDelimitedFileMap(int version, int level, String customPrefix, IOssFileProcess iOssFileProcessor) throws QiniuException {

        ListBucket listBucket = new ListBucket(auth, configuration);
        Map<String, String> delimitedFileMap;
        List<String> prefixList = Arrays.asList(" !\"#$%&'()*+,-./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~".split(""));
        if (!StringUtils.isNullOrEmpty(customPrefix))
            prefixList = prefixList.parallelStream().map(prefix -> customPrefix + prefix).collect(Collectors.toList());
        if (level == 2) {
            delimitedFileMap = listByPrefix(listBucket, prefixList, version, false, null);
            prefixList = getSecondFilePrefix(prefixList, delimitedFileMap);
            delimitedFileMap.putAll(listByPrefix(listBucket, prefixList, version, true, iOssFileProcessor));
        } else {
            delimitedFileMap = listByPrefix(listBucket, prefixList, version, true, iOssFileProcessor);
        }
        listBucket.closeBucketManager();
        fileReaderAndWriterMap.closeWriter();

        return delimitedFileMap;
    }

    /*
    单次列举请求，可以传递 marker 和 limit 参数，通常采用此方法进行并发处理
     */
    public FileListing listV1(ListBucket listBucket, String bucket, String prefix, String delimiter, String marker,
                              int limit, int retryCount) throws QiniuException {

        Response response = listBucket.run(bucket, prefix, delimiter, marker, limit, retryCount, 1);
        FileListing fileListing = response.jsonToObject(FileListing.class);
        response.close();

        return fileListing;
    }

    public Map<String, String> getFileInfoAndMarkerMap(FileListing fileListing) {

        if (fileListing == null) return new HashMap<>();
        return Arrays.asList(fileListing.items).parallelStream()
                .map(item -> getFirstFileInfoAndMarker(null, null, item, 1))
                .collect(Collectors.toMap(
                        fileInfoAndMarker -> fileInfoAndMarker[1],
                        fileInfoAndMarker -> fileInfoAndMarker[2]
        ));
    }

    /*
    v2 的 list 接口，接收到响应后通过 java8 的流来处理响应的文本流。
     */
    public Map<String, String> listV2(ListBucket listBucket, String bucket, String prefix, String delimiter, String marker,
                         int limit, int retryCount) throws IOException {

        Response response = listBucket.run(bucket, prefix, delimiter, marker, limit, retryCount, 2);
        InputStream inputStream = new BufferedInputStream(response.bodyStream());
        Reader reader = new InputStreamReader(inputStream);
        BufferedReader bufferedReader = new BufferedReader(reader);
        Stream<String> lineStream = bufferedReader.lines().parallel();
        Map<String, String> fileInfoAndMarkerMap = lineStream.
                map(line -> getFirstFileInfoAndMarker(null, line, null, 2))
                .collect(Collectors.toMap(
                        fileInfoAndMarker -> fileInfoAndMarker[1],
                        fileInfoAndMarker -> fileInfoAndMarker[2]
        ));
        bufferedReader.close();
        reader.close();
        inputStream.close();
        response.close();

        return fileInfoAndMarkerMap;
    }

    public String getNextMarker(Map<String, String> fileInfoAndMarkerMap, String fileFlag, int unitLen, int version) {

        if (fileInfoAndMarkerMap == null || fileInfoAndMarkerMap.size() < unitLen) {
            return null;
        } else if (!StringUtils.isNullOrEmpty(fileFlag) && fileInfoAndMarkerMap.keySet().parallelStream()
                .anyMatch(fileInfo -> JsonConvertUtils.fromJson(fileInfo, FileInfo.class).key.equals(fileFlag)))
        {
            return null;
        } else {
            // version 1 的 null 为结束标志，返回 "" 时会进一步判断
            if (version == 1) return "";
            Optional<String> lastFileInfo = fileInfoAndMarkerMap.keySet().parallelStream().max(String::compareTo);
            return lastFileInfo.isPresent() ? fileInfoAndMarkerMap.get(lastFileInfo.get()) : null;
        }
    }

    public Queue<QiniuException> processFileInfo(Stream<String> fileInfoStream, IOssFileProcess iOssFileProcessor,
                                                 boolean processBatch, int retryCount, Queue<QiniuException> exceptionQueue) {

        if (exceptionQueue != null) {
            fileInfoStream.forEach(fileInfo -> {
                iOssFileProcessor.processFile(fileInfo, retryCount, processBatch);
                if (iOssFileProcessor.qiniuException() != null && iOssFileProcessor.qiniuException().code() > 400)
                    exceptionQueue.add(iOssFileProcessor.qiniuException());
            });
        } else {
            fileInfoStream.forEach(fileInfo -> iOssFileProcessor.processFile(fileInfo, retryCount, processBatch));
        }

        return exceptionQueue;
    }

    public String listAndProcess(ListBucket listBucket, int unitLen, String prefix, String endFileKey, String marker, int version,
                                  FileReaderAndWriterMap fileMap, IOssFileProcess processor, boolean processBatch) throws IOException {

        Map<String, String> fileInfoAndMarkerMap = new HashMap<>();
        if (version == 2) {
            fileInfoAndMarkerMap = listV2(listBucket, bucket, prefix, "", marker, unitLen, 3);
            marker = getNextMarker(fileInfoAndMarkerMap, endFileKey, unitLen, 2);
        } else if (version == 1) {
            FileListing fileListing = listV1(listBucket, bucket, prefix, "", marker, unitLen, 3);
            fileInfoAndMarkerMap = getFileInfoAndMarkerMap(fileListing);
            marker = getNextMarker(fileInfoAndMarkerMap, endFileKey, unitLen, 1) != null ? fileListing.marker : null;
        }
        List<String> fileInfoList = (StringUtils.isNullOrEmpty(endFileKey) ? fileInfoAndMarkerMap.keySet().parallelStream() :
                fileInfoAndMarkerMap.keySet().parallelStream().filter(
                        fileInfo -> JsonConvertUtils.fromJson(fileInfo, FileInfo.class).key.compareTo(endFileKey) < 0
                )).collect(Collectors.toList());
        if (fileMap != null) fileMap.writeSuccess(String.join("\n", fileInfoList));
        if (processor != null) processFileInfo(fileInfoList.parallelStream(), processor, processBatch, 3, null);

        return marker;
    }

    public void processBucket(int version, int maxThreads, int level, int unitLen, boolean endFile, String customPrefix,
                              IOssFileProcess iOssFileProcessor, boolean processBatch) throws IOException, CloneNotSupportedException {

        Map<String, String> delimitedFileMap = getDelimitedFileMap(version, level, customPrefix, iOssFileProcessor);
        List<String> keyList = new ArrayList<>(delimitedFileMap.keySet());
        Collections.sort(keyList);
        boolean strictPrefix = !StringUtils.isNullOrEmpty(customPrefix);
        int runningThreads = strictPrefix ? delimitedFileMap.size() : delimitedFileMap.size() + 1;
        runningThreads = runningThreads < maxThreads ? runningThreads : maxThreads;
        System.out.println("there are " + runningThreads + " threads running...");

        ExecutorService executorPool = Executors.newFixedThreadPool(runningThreads);
        for (int i = strictPrefix ? 0 : -1; i < keyList.size(); i++) {
            int finalI = i;
            FileReaderAndWriterMap fileMap = new FileReaderAndWriterMap();
            fileMap.initWriter(resultFileDir, "list");
            IOssFileProcess processor = iOssFileProcessor != null ? iOssFileProcessor.clone() : null;
            executorPool.execute(() -> {
                String endFileKey = "";
                String prefix = "";
                if (endFile && finalI < keyList.size() - 1) {
                    endFileKey = keyList.get(finalI + 1);
                } else if (!endFile && finalI < keyList.size() -1 && finalI > -1) {
                    if (keyList.get(finalI).length() < customPrefix.length() + 2) prefix = keyList.get(finalI);
                    else prefix = keyList.get(finalI).substring(0, customPrefix.length() + level == 2 ? 2 : 1);
                } else {
                    if (finalI == -1) endFileKey = keyList.get(0);
                    if (strictPrefix) prefix = customPrefix;
                }
                String marker = finalI == -1 ? "null" : delimitedFileMap.get(keyList.get(finalI));
                ListBucket listBucket = new ListBucket(auth, configuration);
                while (!StringUtils.isNullOrEmpty(marker)) {
                    try {
                        marker = listAndProcess(listBucket, unitLen, prefix, endFileKey,
                                marker.equals("null") ? "" : marker, version, fileMap, processor, processBatch);
                    } catch (IOException e) {
                        fileMap.writeErrorOrNull(bucket + "\t" + prefix + endFileKey + "\t" + marker + "\t" + unitLen
                                + "\t" + e.getMessage());
                    }
                }
                listBucket.closeBucketManager();
                if (processor != null) {
                    processor.checkBatchProcess(3);
                    processor.closeResource();
                }
                fileMap.closeWriter();
            });
        }

        executorPool.shutdown();
        try {
            while (!executorPool.isTerminated())
                Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}