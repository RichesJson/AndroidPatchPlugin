package com.xingshulin.singularity.patch

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import okhttp3.*
import org.gradle.api.GradleException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static groovy.json.JsonOutput.toJson
import static java.net.URLEncoder.encode
import static okhttp3.MediaType.parse
import static okhttp3.RequestBody.create
import static org.apache.commons.codec.digest.DigestUtils.shaHex

class PatchUploader {
    public static final String KEY_BUILD_TIMESTAMP = 'buildTimestamp'
    public static final String KEY_REVISION_CODE = 'revisionCode'
    public static final String KEY_PACKAGE_NAME = 'packageName'
    public static final String KEY_VERSION_CODE = 'versionCode'
    public static final String KEY_VERSION_NAME = 'versionName'
    public static final String KEY_BUILD_DEVICE_ID = 'buildDeviceId'

    static private OkHttpClient client = new OkHttpClient()
    static private String host = "http://singularity.xingshulin.com"
    static private Logger logger = LoggerFactory.getLogger('android-patch')

    static private String securityKey

    static void setAccessKey(String accessKey) {
        if (accessKey == null || accessKey.trim().length() == 0) {
            def info = 'Access key cannot be null.'
            logger.quiet(fatal(info))
            throw new GradleException(info)
        }
        securityKey = accessKey
    }

    private static Object downloadBuildHistories(HashMap<String, String> buildOptions) {
        def params = buildOptions.collect { key, value ->
            return "${key}=${encode(value.toString(), "UTF-8")}"
        }
        def request = new Request.Builder()
                .url("${host}/buildHistories?${params.join('&')}")
                .header("Authorization", "Bearer ${securityKey}")
                .build()
        def response = client.newCall(request).execute()
        failBuildOnError(response)
        def result = response.body().string()
        logger.quiet(JsonOutput.prettyPrint(result))
        new JsonSlurper().parseText(result)
    }

    static void uploadBuildHistory(HashMap<String, String> buildHistorySettings, String fileName) {
        logger.quiet('Upload build params ' + toJson(buildHistorySettings))
        def builder = new Request.Builder()
                .url("${host}/buildHistories")
                .header("Authorization", "Bearer ${securityKey}")
        def formBuilder = new FormBody.Builder()
        for (String key : buildHistorySettings.keySet()) {
            formBuilder.addEncoded(key, buildHistorySettings.get(key))
        }
        formBuilder.addEncoded("dexMapping", fileName)
        def request = builder.post(formBuilder.build()).build()
        def response = client.newCall(request).execute()
        failBuildOnError(response)
        logger.debug(response.body().string())
    }

    private static String getToken(String tokenType, String fileName) {
        def builder = new Request.Builder()
                .url("${host}/tokens?type=${tokenType}&key=${fileName}")
                .header("Authorization", "Bearer ${securityKey}")
        def response = client.newCall(builder.build()).execute()

        failBuildOnError(response)
        String uploadToken = response.body().string()
        if (!uploadToken) {
            def info = 'Cannot get upload token, please check your network.'
            logger.quiet(fatal(info))
            throw new GradleException(info)
        }
        uploadToken
    }

    static void uploadPatch(Map<String, String> patchOptions, File patchFile) {
        logger.quiet('Patch file created @ ' + patchFile.absolutePath)
        String uploadToken = getToken("put", patchFile.name)
        uploadFile(uploadToken, patchFile)
        uploadPatchInfo(patchOptions, patchFile)
    }

    static void uploadPatchInfo(Map<String, String> patchOptions, File patchFile) {
        logger.quiet('Upload patch info ' + toJson(patchOptions))
        def builder = new Request.Builder()
                .url("${host}/patches")
                .header("Authorization", "Bearer ${securityKey}")
        def formBuilder = new FormBody.Builder()
        formBuilder.addEncoded('packageName', patchOptions.get(KEY_PACKAGE_NAME))
        formBuilder.addEncoded('appVersion', patchOptions.get(KEY_VERSION_NAME))
        formBuilder.addEncoded('appBuild', patchOptions.get(KEY_VERSION_CODE))
        formBuilder.addEncoded('version', '1')
        formBuilder.addEncoded("uri", patchFile.name)
        formBuilder.addEncoded("sha1", shaHex(patchFile.bytes))
        formBuilder.addEncoded("buildDeviceId", patchOptions.get(KEY_BUILD_DEVICE_ID))
        formBuilder.addEncoded("buildTimestamp", patchOptions.get(KEY_BUILD_TIMESTAMP))
        def request = builder.post(formBuilder.build()).build()
        def response = client.newCall(request).execute()
        failBuildOnError(response)
        logger.debug(response.body().string())
    }

    private static void uploadFile(String uploadToken, File patchedFiles) {
        def body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart('token', uploadToken)
                .addFormDataPart('file', patchedFiles.name, create(parse('text/plain; charset=utf-8'), patchedFiles))
                .addFormDataPart('key', patchedFiles.name)
                .build()
        def request = new Request.Builder()
                .url("http://upload.qiniu.com/")
                .header('Host', 'upload.qiniu.com')
                .post(body).build()
        def response = client.newCall(request).execute()
        failBuildOnError(response)
        logger.debug(response.body().string())
    }

    static HashMap<String, String> downloadBuildHistory(HashMap<String, String> buildOptions, String patchDir) {
        Object result = downloadBuildHistories(buildOptions)
        logger.quiet(formatBuildHistories(result))
        if (!result[0] || result.size() > 1) {
            return new HashMap<String, String>(0)
        }

        String mapping = result[0]["dexMapping"]
        logger.debug("Found mapping file ${mapping}")
        def token = getToken("get", mapping)

        def request = new Request.Builder().url(token).build()
        def response = client.newCall(request).execute()

        def patchedTxt = new File("${patchDir}/${mapping}")
        if (patchedTxt.exists()) patchedTxt.delete()

        failBuildOnError(response)
        def bytes = response.body().bytes()
        patchedTxt.bytes = bytes
        logger.quiet("Downloaded mapping file ${patchedTxt.absolutePath}")
        return (HashMap<String, String>) new JsonSlurper().parse(bytes)
    }

    static void saveBuildHistory(HashMap<String, String> buildOptions, File patchClasses) {
        String uploadToken = getToken("put", patchClasses.name)
        uploadFile(uploadToken, patchClasses)
        uploadBuildHistory(buildOptions, patchClasses.name)
    }

    private static void failBuildOnError(Response response) {
        if (!response.successful) {
            def error = response.body().string()
            logger.quiet(fatal(error))
            throw new GradleException(error)
        }
    }

    static String formatBuildHistories(Object jsonArray) {
        if (jsonArray.size() == 0) {
            return fatal('No build histories found, please adjust your filters.')
        }
        return fatal("Found ${jsonArray.size()} build histories.")
    }

    private static String fatal(String info) {
        return "==================\r\nFatal: $info \r\n================="
    }
}
