package com.qiniu.service;

import com.qiniu.common.FileReaderAndWriterMap;
import com.qiniu.common.QiniuAuth;
import com.qiniu.common.QiniuSuitsException;
import com.qiniu.service.auvideo.M3U8Manager;
import com.qiniu.service.auvideo.VideoTS;
import com.qiniu.service.jedi.IProcessInterface;
import com.qiniu.service.oss.AsyncFetchProcessor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FetchProcess implements IProcessInterface {

    private AsyncFetchProcessor asyncFetchProcessor;
    private FileReaderAndWriterMap targetFileReaderAndWriterMap;
    private M3U8Manager m3u8Manager;

    public FetchProcess(QiniuAuth auth, String targetBucket, FileReaderAndWriterMap targetFileReaderAndWriterMap) throws QiniuSuitsException {
        this.asyncFetchProcessor = AsyncFetchProcessor.getAsyncFetchProcessor(auth, targetBucket);
        this.targetFileReaderAndWriterMap = targetFileReaderAndWriterMap;
    }

    public FetchProcess(QiniuAuth auth, String targetBucket, FileReaderAndWriterMap targetFileReaderAndWriterMap, M3U8Manager m3u8Manager) throws QiniuSuitsException {
        this.asyncFetchProcessor = AsyncFetchProcessor.getAsyncFetchProcessor(auth, targetBucket);
        this.targetFileReaderAndWriterMap = targetFileReaderAndWriterMap;
        this.m3u8Manager = m3u8Manager;
    }

    private void fetchResult(String url, String key) {
        try {
            String fetchResult = asyncFetchProcessor.doAsyncFetch(url, key);
            targetFileReaderAndWriterMap.writeSuccess(fetchResult);
        } catch (QiniuSuitsException e) {
            targetFileReaderAndWriterMap.writeErrorAndNull(e.toString() + "\t" + url + "," + key);
        }
    }

    public void processItem(String rootUrl, String item) {
        processItem(rootUrl, item, item);
    }

    public void processItem(String rootUrl, String item, String key) {
        String url = rootUrl.endsWith("/") ? rootUrl + item : rootUrl + "/" + item;
        fetchResult(url, key);
    }

    public void processItem(QiniuAuth auth, String rootUrl, String item) {
        processItem(auth, rootUrl, item, item);
    }

    public void processItem(QiniuAuth auth, String rootUrl, String item, String key) {
        String url = auth.privateDownloadUrl(rootUrl + item);
        fetchResult(url, key);
    }

    public void processUrl(String url, String key) {
        fetchResult(url, key);
    }

    public void processUrl(String url, String key, String format) {
        processUrl(url, key);

        if (Arrays.asList("hls", "HLS", "m3u8", "M3U8").contains(format)) {
            fetchTSByM3U8(url);
        }
    }

    public void processUrl(QiniuAuth auth, String url, String key) {
        url = auth.privateDownloadUrl(url);
        fetchResult(url, key);
    }

    public void processUrl(QiniuAuth auth, String url, String key, String format) {
        url = auth.privateDownloadUrl(url);
        processUrl(url, key);

        if (Arrays.asList("hls", "HLS", "m3u8", "M3U8").contains(format)) {
            fetchTSByM3U8(url);
        }
    }

    public void fetchTSByM3U8(String rootUrl, String m3u8FilePath) {
        List<VideoTS> videoTSList = new ArrayList<>();

        try {
            videoTSList = m3u8Manager.getVideoTSListByFile(rootUrl, m3u8FilePath);
        } catch (IOException ioException) {
            targetFileReaderAndWriterMap.writeOther("list ts failed: " + m3u8FilePath);
        }

        for (VideoTS videoTS : videoTSList) {
            processUrl(videoTS.getUrl(), videoTS.getUrl().substring(videoTS.getUrl().indexOf("/", 8) + 1));
        }
    }

    public void fetchTSByM3U8(String m3u8Url) {
        List<VideoTS> videoTSList = new ArrayList<>();

        try {
            videoTSList = m3u8Manager.getVideoTSListByUrl(m3u8Url);
        } catch (IOException ioException) {
            targetFileReaderAndWriterMap.writeOther("list ts failed: " + m3u8Url);
        }

        for (VideoTS videoTS : videoTSList) {
            processUrl(videoTS.getUrl(), videoTS.getUrl().substring(videoTS.getUrl().indexOf("/", 8) + 1));
        }
    }

    public void close() {
        if (asyncFetchProcessor != null) {
            asyncFetchProcessor.closeClient();
        }
    }
}