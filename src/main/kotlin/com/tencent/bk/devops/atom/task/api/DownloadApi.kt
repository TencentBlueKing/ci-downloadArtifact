package com.tencent.bk.devops.atom.task.api

import com.tencent.bk.devops.atom.api.BaseApi
import com.tencent.bk.devops.atom.api.SdkEnv
import com.tencent.bk.devops.atom.exception.AtomException
import com.tencent.bk.devops.atom.pojo.Result
import com.tencent.bk.devops.atom.task.pojo.BuildHistory
import com.tencent.bk.devops.atom.task.pojo.QueryData
import com.tencent.bk.devops.atom.task.pojo.QueryNodeInfo
import com.tencent.bk.devops.atom.task.utils.FileDownloader
import com.tencent.bkrepo.common.api.constant.HttpHeaders
import com.tencent.bkrepo.common.api.constant.MediaTypes
import com.tencent.bkrepo.common.api.constant.StringPool
import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.api.util.readJsonString
import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.common.artifact.path.PathUtils
import com.tencent.bkrepo.common.query.enums.OperationType
import com.tencent.bkrepo.common.query.model.PageLimit
import com.tencent.bkrepo.common.query.model.QueryModel
import com.tencent.bkrepo.common.query.model.Rule
import com.tencent.bkrepo.common.query.model.Sort
import com.tencent.bkrepo.generic.pojo.TemporaryAccessToken
import com.tencent.bkrepo.repository.pojo.token.TemporaryTokenCreateRequest
import com.tencent.bkrepo.repository.pojo.token.TokenType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DownloadApi : BaseApi() {
    private val atomHttpClient = AtomHttpClient()
    private val fileGateway by lazy { SdkEnv.getFileGateway() }
    private val tokenRequest by lazy { fileGateway.isNotBlank() }
    private var token: String = ""


    fun download(
        userId: String,
        projectId: String,
        repoName: String,
        path: String,
        destPath: File,
        size: Long,
        rangeDownload: Boolean,
        ignoreDigestCheck: Boolean
    ) {
        downloadFile(
            userId = userId,
            projectId = projectId,
            repoName = repoName,
            path = path,
            destPath = destPath,
            size = size,
            rangeDownloader = rangeDownload,
            ignoreDigestCheck = ignoreDigestCheck
        )
    }

    private fun downloadFile(
        userId: String,
        projectId: String,
        repoName: String,
        path: String,
        destPath: File,
        size: Long,
        rangeDownloader: Boolean,
        ignoreDigestCheck: Boolean
    ) {
        val encodedPath = urlEncode(path)
        val url = if (tokenRequest) {
            token = createToken(userId, projectId, repoName)
            "$fileGateway/generic/temporary/download/$projectId/$repoName/$encodedPath?token=$token"
        } else {
            "/bkrepo/api/build/generic/$projectId/$repoName/$encodedPath"
        }
        val headers = mutableMapOf(HEADER_BKREPO_UID to userId)
        if (token.isNotBlank()) {
            headers[HttpHeaders.AUTHORIZATION] = "Temporary $token"
        }
        val request = atomHttpClient.buildAtomGet(url, headers)
        if (rangeDownloader && size > 0) {
            val fileDownloader = FileDownloader(request, destPath, ignoreDigestCheck)
            fileDownloader.removeFiles()
            val action = { fileDownloader.downloadFile() }
            val result = retry(action)
            if (result.isNotEmpty()) {
                throw AtomException("download error: $result")
            }
        } else {
            logger.info("using default method to download...")
            download(request, destPath, size)
        }
    }

    fun download(request: Request, destPath: File, size: Long) {
        okHttpClient.newBuilder().addInterceptor {
            logger.info("url: ${it.request().url}")
            it.proceed(it.request())
        }.build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AtomException("get $request failed, response code: ${response.code}")
            }
            if (!destPath.parentFile.exists()) {
                destPath.parentFile.mkdirs()
            }
            val target = destPath.canonicalPath
            logger.info("save file to: $target, size $size byte(s)")
            var readBytes = 0L
            val doubleSize = size.toDouble()
            val startTime = System.currentTimeMillis()
            response.body!!.byteStream().use { bs ->
                val buf = ByteArray(4096)
                var logTime = System.currentTimeMillis()
                var len = bs.read(buf)
                FileOutputStream(destPath).use { fos ->
                    logger.info("$target >>> 0.0%\n")
                    while (len != -1) {
                        fos.write(buf, 0, len)
                        len = bs.read(buf)
                        readBytes += len
                        val now = System.currentTimeMillis()
                        if ((now - logTime) > 3000) {
                            logger.info("$target >>> ${String.format("%.1f", readBytes / doubleSize * 100)}%\n")
                            logTime = now
                        }
                    }
                    logger.info("$target >>> 100%\n")
                }
            }
            logger.info("file transfer time: ${String.format("%.2f", (System.currentTimeMillis() - startTime) / 1000.0)} second(s)")
        }
    }

    private fun createToken(userId: String, projectId: String, repoName: String): String {
        if (token.isNotBlank()) {
            return token
        }
        val tokenCreateRequest = TemporaryTokenCreateRequest(
            projectId,
            repoName,
            setOf(StringPool.ROOT),
            setOf(userId),
            emptySet(),
            TimeUnit.DAYS.toSeconds(1),
            null,
            TokenType.ALL
        )
        val request = buildPost(
            "/bkrepo/api/build/generic/temporary/token/create",
            tokenCreateRequest.toJsonString().toRequestBody(MediaTypes.APPLICATION_JSON.toMediaTypeOrNull()),
            mutableMapOf(HEADER_BKREPO_UID to userId)
        )
        val (status, response) = doRequest(request)
        if (status == 200) {
            return response.readJsonString<Response<List<TemporaryAccessToken>>>().data!!.first().token
        } else {
            throw AtomException(response)
        }
    }

    /**
     * 重试
     */
    private fun retry(f: () -> Unit, times: Int = 3, delay: Long = 500) =
        (0..times).fold("") { _, n ->
            try {
                logger.info("try the $n time to download file..")
                f()
                return ""
            } catch (e: Exception) {
                Thread.sleep(delay)
                "${e.message}"
            }
        }

    fun getLatestSuccessBuild(projectId: String, pipelineId: String): BuildHistory? {
        val url = "/process/api/build/builds/$projectId/$pipelineId/latestSuccessBuild"
        val request = atomHttpClient.buildAtomGet(url)
        val responseContent = atomHttpClient.doRequestWithContent(request)
        val result: Result<BuildHistory?> = responseContent.readJsonString()
        return result.data
    }

    fun getSingleBuildHistory(projectId: String, pipelineId: String, buildNum: String): BuildHistory? {
        val url = "/process/api/build/builds/$projectId/$pipelineId/$buildNum/history?channelCode=BS"
        val request = atomHttpClient.buildAtomGet(url)
        val responseContent = atomHttpClient.doRequestWithContent(request)
        val result: Result<BuildHistory?> = responseContent.readJsonString()
        return result.data
    }


    fun matchFiles(userId: String, projectId: String, repoName: String, path: String): List<QueryNodeInfo> {
        val normalizedPath = "/${path.removePrefix("./").removePrefix("/")}"
        val filePath = PathUtils.resolveParent(normalizedPath)
        val fileName = PathUtils.resolveName(normalizedPath)
        return queryByPathEqNameMatchMetadataEqAnd(
            userId = userId,
            projectId = projectId,
            repoName = repoName,
            path = filePath,
            name = fileName,
            metadata = mapOf(),
            page = 0,
            pageSize = 10000
        )
    }

    private fun queryByPathEqNameMatchMetadataEqAnd(
        userId: String,
        projectId: String, // eq
        repoName: String, // eq
        path: String, // eq
        name: String, // match
        metadata: Map<String, String>, // eq and
        page: Int,
        pageSize: Int
    ): List<QueryNodeInfo> {
        val projectRule = Rule.QueryRule("projectId", projectId, OperationType.EQ)
        val repoRule = Rule.QueryRule("repoName", repoName, OperationType.EQ)
        val pathRule = Rule.QueryRule("path", path, OperationType.EQ)
        val nameRule = Rule.QueryRule("name", name, OperationType.MATCH)
        val ruleList = mutableListOf<Rule>(
            projectRule, repoRule, Rule.QueryRule("folder", false, OperationType.EQ),
            pathRule, nameRule
        )
        if (metadata.isNotEmpty()) {
            val metadataRule =
                Rule.NestedRule(
                    metadata.map { Rule.QueryRule("metadata.${it.key}", it.value, OperationType.EQ) }
                        .toMutableList(),
                    Rule.NestedRule.RelationType.AND
                )
            ruleList.add(metadataRule)
        }
        val rule = Rule.NestedRule(ruleList, Rule.NestedRule.RelationType.AND)
        val queryModel = QueryModel(
            page = PageLimit(page, pageSize),
            sort = Sort(listOf("fullPath"), Sort.Direction.ASC),
            select = mutableListOf(),
            rule = rule
        )
        return query(userId, queryModel)
    }

    private fun query(userId: String, queryModel: QueryModel): List<QueryNodeInfo> {
        val url = "/bkrepo/api/build/repository/api/node/search"
        val request = buildPost(
            url,
            queryModel.toJsonString().toRequestBody(MediaTypes.APPLICATION_JSON.toMediaTypeOrNull()),
            mutableMapOf(HEADER_BKREPO_UID to userId)
        )
        logger.debug("search request body: ${queryModel.toJsonString().replace(System.lineSeparator(), "")}")
        atomHttpClient.doRequest(request).use { response ->
            val responseContent = response.body!!.string()
            val responseData = responseContent.readJsonString<Response<QueryData>>()
            if (!response.isSuccessful) {
                logger.error("query failed, responseContent: $responseContent")
                throw AtomException("query failed: ${responseData.message}")
            }

            return responseData.data!!.records
        }
    }

    private fun doRequest(request: Request, retry: Int = 3): Pair<Int,String> {
        try {
            val response = atomHttpClient.doRequest(request)
            val responseContent = response.body!!.string()
            if (response.isSuccessful) {
                return Pair(response.code, responseContent)
            }
            logger.debug("request url: ${request.url}, code: ${response.code}, response: $responseContent")
            if (response.code > 499 && retry > 0) {
                return doRequest(request, retry - 1)
            }
            return Pair(response.code, responseContent)
        } catch (e: IOException) {
            logger.error("request[${request.url}] error, ", e)
            if (retry > 0) {
                logger.info("retry after 3 seconds")
                Thread.sleep(3 * 1000L)
                return doRequest(request, retry - 1)
            }
            throw e
        }
    }

    private fun urlEncode(str: String?): String {
        return if (str.isNullOrBlank()) {
            ""
        } else {
            URLEncoder.encode(str, Charsets.UTF_8.toString()).replace("+", "%20")
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DownloadApi::class.java)
        private const val HEADER_BKREPO_UID = "X-BKREPO-UID"
    }
}
