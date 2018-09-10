package com.qiniu.common;

import com.qiniu.http.Client;
import com.qiniu.http.Response;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.model.FetchRet;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.storage.model.FileListing;
import com.qiniu.util.StringMap;
import com.qiniu.util.StringUtils;
import com.qiniu.util.UrlSafeBase64;

import java.util.Map;

/**
 * 主要涉及了空间资源管理及批量操作接口的实现，具体的接口规格可以参考
 * 参考文档：<a href="http://developer.qiniu.com/kodo/api/rs">资源管理</a>
 */
public class QiniuBucketManager {
    /**
     * Auth 对象
     * 该类需要使用QBox鉴权，所以需要指定Auth对象
     */
    private final QiniuAuth auth;

    /**
     * Configuration 对象
     * 该类相关的域名配置，解析配置，HTTP请求超时时间设置等
     */

    private final Configuration configuration;

    /**
     * HTTP Client 对象
     * 该类需要通过该对象来发送HTTP请求
     */
    private final Client client;

    /**
     * 构建一个新的 BucketManager 对象
     *
     * @param auth Auth对象
     * @param cfg  Configuration对象
     */
    public QiniuBucketManager(QiniuAuth auth, Configuration cfg) {
        this.auth = auth;
        this.configuration = cfg.clone();
        client = new Client(this.configuration);
    }

    /**
     * EncodedEntryURI格式，其中 bucket+":"+key 称之为 entry
     *
     * @param bucket
     * @param key
     * @return UrlSafeBase64.encodeToString(entry)
     * @link http://developer.qiniu.com/kodo/api/data-format
     */
    public static String encodedEntry(String bucket, String key) {
        String encodedEntry;
        if (key != null) {
            encodedEntry = UrlSafeBase64.encodeToString(bucket + ":" + key);
        } else {
            encodedEntry = UrlSafeBase64.encodeToString(bucket);
        }
        return encodedEntry;
    }

    /**
     * EncodedEntryURI格式，用在不指定key值的情况下
     *
     * @param bucket 空间名称
     * @return UrlSafeBase64.encodeToString(bucket)
     * @link http://developer.qiniu.com/kodo/api/data-format
     */
    public static String encodedEntry(String bucket) {
        return encodedEntry(bucket, null);
    }


    /**
     * 获取账号下所有空间名称列表
     *
     * @return 空间名称列表
     */
    public String[] buckets() throws QiniuException {
        // 获取 bucket 列表 写死用rs.qiniu.com or rs.qbox.me @冯立元
        String url = String.format("%s/buckets", configuration.rsHost());
        Response r = get(url);
        return r.jsonToObject(String[].class);
    }

    public void createBucket(String bucketName, String region) throws Exception {
        String url = String.format("%s/mkbucketv2/%s/region/%s", configuration.rsHost(),
                UrlSafeBase64.encodeToString(bucketName), region);
        post(url, null);
    }

    /**
     * 获取该空间下所有的domain
     *
     * @param bucket
     * @return 该空间名下的domain
     * @throws QiniuException
     */

    public String[] domainList(String bucket) throws QiniuException {
        String url = String.format("%s/v6/domain/list?tbl=%s", configuration.apiHost(), bucket);
        Response r = get(url);
        return r.jsonToObject(String[].class);
    }

    /**
     * 根据前缀获取文件列表
     *
     * @param bucket    空间名
     * @param prefix    文件名前缀
     * @param marker    上一次获取文件列表时返回的 marker
     * @param limit     每次迭代的长度限制，最大1000，推荐值 100
     * @param delimiter 指定目录分隔符，列出所有公共前缀（模拟列出目录效果）。缺省值为空字符串
     * @return
     * @throws QiniuException
     */
    public FileListing listFiles(String bucket, String prefix, String marker, int limit, String delimiter)
            throws QiniuException {
        StringMap map = new StringMap().put("bucket", bucket).putNotEmpty("marker", marker)
                .putNotEmpty("prefix", prefix).putNotEmpty("delimiter", delimiter).putWhen("limit", limit, limit > 0);

        String url = String.format("%s/list?%s", configuration.rsfHost(auth.accessKey, bucket), map.formString());
        Response r = get(url);
        return r.jsonToObject(FileListing.class);
    }

    /**
     * 获取空间中文件的属性
     *
     * @param bucket  空间名称
     * @param fileKey 文件名称
     * @return 文件属性
     * @throws QiniuException
     * @link http://developer.qiniu.com/kodo/api/stat
     */
    public FileInfo stat(String bucket, String fileKey) throws QiniuException {
        Response r = rsGet(bucket, String.format("/stat/%s", encodedEntry(bucket, fileKey)));
        return r.jsonToObject(FileInfo.class);
    }

    /**
     * 删除指定空间、文件名的文件
     *
     * @param bucket 空间名称
     * @param key    文件名称
     * @throws QiniuException
     * @link http://developer.qiniu.com/kodo/api/delete
     */
    public Response delete(String bucket, String key) throws QiniuException {
        return rsPost(bucket, String.format("/delete/%s", encodedEntry(bucket, key)), null);
    }

    /**
     * 修改文件的MimeType
     *
     * @param bucket 空间名称
     * @param key    文件名称
     * @param mime   文件的新MimeType
     * @throws QiniuException
     * @link http://developer.qiniu.com/kodo/api/chgm
     */
    public Response changeMime(String bucket, String key, String mime)
            throws QiniuException {
        String resource = encodedEntry(bucket, key);
        String encodedMime = UrlSafeBase64.encodeToString(mime);
        String path = String.format("/chgm/%s/mime/%s", resource, encodedMime);
        return rsPost(bucket, path, null);
    }

    /**
     * 修改文件的元数据
     *
     * @param bucket  空间名称
     * @param key     文件名称
     * @param headers 需要修改的文件元数据
     * @throws QiniuException
     * @link https://developer.qiniu.com/kodo/api/1252/chgm
     */
    public Response changeHeaders(String bucket, String key, Map<String, String> headers)
            throws QiniuException {
        String resource = encodedEntry(bucket, key);
        String path = String.format("/chgm/%s", resource);
        for (String k : headers.keySet()) {
            String encodedMetaValue = UrlSafeBase64.encodeToString(headers.get(k));
            path = String.format("%s/x-qn-meta-!%s/%s", path, k, encodedMetaValue);
        }
        return rsPost(bucket, path, null);
    }


    //存储类型
    public enum StorageType {
        //普通存储
        COMMON,
        //低频存储
        INFREQUENCY
    }

    /**
     * 修改文件的类型（普通存储或低频存储）
     *
     * @param bucket 空间名称
     * @param key    文件名称
     * @param type   type=0 表示普通存储，type=1 表示低频存存储
     * @throws QiniuException
     */
    public Response changeType(String bucket, String key, com.qiniu.storage.BucketManager.StorageType type)
            throws QiniuException {
        String resource = encodedEntry(bucket, key);
        String path = String.format("/chtype/%s/type/%d", resource, type.ordinal());
        return rsPost(bucket, path, null);
    }

    /**
     * 重命名空间中的文件，可以设置force参数为true强行覆盖空间已有同名文件
     *
     * @param bucket     空间名称
     * @param oldFileKey 文件名称
     * @param newFileKey 新文件名
     * @param force      强制覆盖空间中已有同名（和 newFileKey 相同）的文件
     * @throws QiniuException
     */
    public Response rename(String bucket, String oldFileKey, String newFileKey, boolean force)
            throws QiniuException {
        return move(bucket, oldFileKey, bucket, newFileKey, force);
    }

    /**
     * 重命名空间中的文件
     *
     * @param bucket     空间名称
     * @param oldFileKey 文件名称
     * @param newFileKey 新文件名
     * @throws QiniuException
     * @link http://developer.qiniu.com/kodo/api/move
     */
    public Response rename(String bucket, String oldFileKey, String newFileKey)
            throws QiniuException {
        return move(bucket, oldFileKey, bucket, newFileKey);
    }

    /**
     * 复制文件，要求空间在同一账号下，可以设置force参数为true强行覆盖空间已有同名文件
     *
     * @param fromBucket  源空间名称
     * @param fromFileKey 源文件名称
     * @param toBucket    目的空间名称
     * @param toFileKey   目的文件名称
     * @param force       强制覆盖空间中已有同名（和 toFileKey 相同）的文件
     * @throws QiniuException
     */
    public Response copy(String fromBucket, String fromFileKey, String toBucket, String toFileKey, boolean force)
            throws QiniuException {
        String from = encodedEntry(fromBucket, fromFileKey);
        String to = encodedEntry(toBucket, toFileKey);
        String path = String.format("/copy/%s/%s/force/%s", from, to, force);
        return rsPost(fromBucket, path, null);
    }

    /**
     * 复制文件，要求空间在同一账号下
     *
     * @param fromBucket  源空间名称
     * @param fromFileKey 源文件名称
     * @param toBucket    目的空间名称
     * @param toFileKey   目的文件名称
     * @throws QiniuException
     */
    public void copy(String fromBucket, String fromFileKey, String toBucket, String toFileKey)
            throws QiniuException {
        copy(fromBucket, fromFileKey, toBucket, toFileKey, false);
    }


    /**
     * 移动文件，要求空间在同一账号下
     *
     * @param fromBucket  源空间名称
     * @param fromFileKey 源文件名称
     * @param toBucket    目的空间名称
     * @param toFileKey   目的文件名称
     * @param force       强制覆盖空间中已有同名（和 toFileKey 相同）的文件
     * @throws QiniuException
     */
    public Response move(String fromBucket, String fromFileKey, String toBucket,
                         String toFileKey, boolean force) throws QiniuException {
        String from = encodedEntry(fromBucket, fromFileKey);
        String to = encodedEntry(toBucket, toFileKey);
        String path = String.format("/move/%s/%s/force/%s", from, to, force);
        return rsPost(fromBucket, path, null);
    }

    /**
     * 移动文件。要求空间在同一账号下, 可以添加force参数为true强行移动文件。
     *
     * @param fromBucket  源空间名称
     * @param fromFileKey 源文件名称
     * @param toBucket    目的空间名称
     * @param toFileKey   目的文件名称
     * @throws QiniuException
     */
    public Response move(String fromBucket, String fromFileKey, String toBucket, String toFileKey)
            throws QiniuException {
        return move(fromBucket, fromFileKey, toBucket, toFileKey, false);
    }


    /**
     * 抓取指定地址的文件，以指定名称保存在指定空间
     * 要求指定url可访问，大文件不建议使用此接口抓取。可先下载再上传
     * 如果不指定保存的文件名，那么以文件内容的 etag 作为文件名
     *
     * @param url    待抓取的文件链接
     * @param bucket 文件抓取后保存的空间
     * @throws QiniuException
     */
    public FetchRet fetch(String url, String bucket) throws QiniuException {
        return fetch(url, bucket, null);
    }

    /**
     * 抓取指定地址的文件，已指定名称保存在指定空间
     * 要求指定url可访问，大文件不建议使用此接口抓取。可先下载再上传
     *
     * @param url    待抓取的文件链接
     * @param bucket 文件抓取后保存的空间
     * @param key    文件抓取后保存的文件名
     * @throws QiniuException
     */
    public FetchRet fetch(String url, String bucket, String key) throws QiniuException {
        String resource = UrlSafeBase64.encodeToString(url);
        String to = encodedEntry(bucket, key);
        String path = String.format("/fetch/%s/to/%s", resource, to);
        Response r = ioPost(bucket, path);
        return r.jsonToObject(FetchRet.class);
    }

    /**
     * 对于设置了镜像存储的空间，从镜像源站抓取指定名称的资源并存储到该空间中
     * 如果该空间中已存在该名称的资源，则会将镜像源站的资源覆盖空间中相同名称的资源
     *
     * @param bucket 空间名称
     * @param key    文件名称
     * @throws QiniuException
     */
    public void prefetch(String bucket, String key) throws QiniuException {
        String resource = encodedEntry(bucket, key);
        String path = String.format("/prefetch/%s", resource);
        ioPost(bucket, path);
    }

    /**
     * 设置空间的镜像源站
     *
     * @param bucket     空间名称
     * @param srcSiteUrl 镜像回源地址
     */
    public Response setImage(String bucket, String srcSiteUrl) throws QiniuException {
        return setImage(bucket, srcSiteUrl, null);
    }

    /**
     * 设置空间的镜像源站
     *
     * @param bucket     空间名称
     * @param srcSiteUrl 镜像回源地址
     * @param host       镜像回源Host
     */
    public Response setImage(String bucket, String srcSiteUrl, String host) throws QiniuException {
        String encodedSiteUrl = UrlSafeBase64.encodeToString(srcSiteUrl);
        String encodedHost = null;
        if (host != null && host.length() > 0) {
            encodedHost = UrlSafeBase64.encodeToString(host);
        }
        String path = String.format("/image/%s/from/%s", bucket, encodedSiteUrl);
        if (encodedHost != null) {
            path += String.format("/host/%s", encodedHost);
        }
        return pubPost(path);
    }

    /**
     * 取消空间的镜像源站设置
     *
     * @param bucket 空间名称
     */
    public Response unsetImage(String bucket) throws QiniuException {
        String path = String.format("/unimage/%s", bucket);
        return pubPost(path);
    }

    /**
     * 设置文件的存活时间
     *
     * @param bucket 空间名称
     * @param key    文件名称
     * @param days   存活时间，单位：天
     */
    public Response deleteAfterDays(String bucket, String key, int days) throws QiniuException {
        return rsPost(bucket, String.format("/deleteAfterDays/%s/%d", encodedEntry(bucket, key), days), null);
    }

    /*
    * 相关请求的方法列表
    * */

    private Response rsPost(String bucket, String path, byte[] body) throws QiniuException {
        check(bucket);
        String url = configuration.rsHost(auth.accessKey, bucket) + path;
        return post(url, body);
    }

    private Response rsGet(String bucket, String path) throws QiniuException {
        check(bucket);
        String url = configuration.rsHost(auth.accessKey, bucket) + path;
        return get(url);
    }

    private Response ioPost(String bucket, String path) throws QiniuException {
        check(bucket);
        String url = configuration.ioHost(auth.accessKey, bucket) + path;
        return post(url, null);
    }

    private Response pubPost(String path) throws QiniuException {
        String url = "http://pu.qbox.me:10200" + path;
        return post(url, null);
    }

    private Response get(String url) throws QiniuException {
        StringMap headers = auth.authorization(url);
        return client.get(url, headers);
    }

    private Response post(String url, byte[] body) throws QiniuException {
        StringMap headers = auth.authorization(url, body, Client.FormMime);
        return client.post(url, body, headers, Client.FormMime);
    }

    private void check(String bucket) throws QiniuException {
        if (StringUtils.isNullOrEmpty(bucket)) {
            throw new QiniuException(Response.createError(null, null, 0, "未指定操作的空间或操作体为空"));
        }
    }

    /**
     * 批量文件管理请求
     */
    public Response batch(com.qiniu.storage.BucketManager.BatchOperations operations) throws QiniuException {
        return rsPost(operations.execBucket(), "/batch", operations.toBody());
    }

}
