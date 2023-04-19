package com.tencent.bk.devops.atom.task

import com.tencent.bk.devops.atom.AtomContext
import com.tencent.bk.devops.atom.exception.AtomException
import com.tencent.bk.devops.atom.pojo.StringData
import com.tencent.bk.devops.atom.spi.AtomService
import com.tencent.bk.devops.atom.spi.TaskAtom
import com.tencent.bk.devops.atom.task.api.DownloadApi
import com.tencent.bk.devops.atom.task.constant.REPO_CUSTOM
import com.tencent.bk.devops.atom.task.constant.REPO_PIPELINE
import com.tencent.bk.devops.atom.task.pojo.BuildHistory
import com.tencent.bkrepo.common.artifact.path.PathUtils
import com.tencent.bk.devops.atom.task.pojo.QueryNodeInfo
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.io.File

@AtomService(paramClass = DownloadArtifactParam::class)
class DownloadArtifactAtom : TaskAtom<DownloadArtifactParam> {
    override fun execute(atomContext: AtomContext<DownloadArtifactParam>) {
        val atomParam = atomContext.param
        when (atomParam.repoName) {
            REPO_CUSTOM -> downloadCustom(atomContext)
            else -> downloadPipeline(atomContext)
        }
    }

    private fun downloadCustom(atomContext: AtomContext<DownloadArtifactParam>) {
        val param = atomContext.param
        val userId = param.pipelineStartUserId
        val repoName = REPO_CUSTOM
        val srcPaths = param.srcPaths.split(",").filter { it.isNotBlank() }
        val projectId = param.projectName
        val bkWorkspace = param.bkWorkspace
        val destPath = PathUtils.normalizePath("$bkWorkspace/${param.destPath}")
        val rangeDownloader = param.rangeDownload
        val ignoreDigestCheck = param.ignoreDigestCheck

        val filterFilesBuilder = StringBuilder()
        val nodeInfoList = mutableListOf<QueryNodeInfo>()
        for (workPath in srcPaths) {
            var path = workPath
            if (path.trim { it <= ' ' }.isEmpty()) {
                continue
            }
            path = path.trim { it <= ' ' }
            path = StringUtils.removeStart(path, "/")
            val matchedFiles = downloadApi.matchFiles(userId, projectId, repoName, path)
            logger.info("$path match ${matchedFiles.size} file: ${matchedFiles.map { it.fullPath }}")
            nodeInfoList.addAll(matchedFiles)
        }
        for (node in nodeInfoList) {
            downloadApi.download(
                userId = userId,
                projectId = projectId,
                repoName = repoName,
                path = node.fullPath,
                destPath = File(destPath, File(node.fullPath).name),
                size = node.size,
                rangeDownload = rangeDownloader,
                ignoreDigestCheck = ignoreDigestCheck
            )
            filterFilesBuilder.append(node.name).append(",")
            logger.info("download file " + node.fullPath + " done")
        }
        if (nodeInfoList.isEmpty()) {
            throw AtomException("0 file found in path: $srcPaths")
        }
    }

    private fun downloadPipeline(atomContext: AtomContext<DownloadArtifactParam>) {
        val param = atomContext.param
        val userId = param.pipelineStartUserId
        val srcPipelineId = param.targetPipelineId
        val downloadLatestBuildNo = param.latestBuildNum
        val targetBuildNo = param.buildNum
        val useLatestSuccessBuild = param.isSuccessfulBuild
        val currentPipelineId = param.pipelineId
        val currentBuildNum = param.pipelineBuildNum
        val projectId = param.projectName
        val srcPaths = param.srcPaths.split(",").filter { it.isNotBlank() }
        val workspace = param.bkWorkspace
        val destPath = PathUtils.normalizePath("$workspace/${param.destPath}")
        val rangeDownloader = param.rangeDownload
        val ignoreDigestCheck = param.ignoreDigestCheck

        val buildHistory = if (!downloadLatestBuildNo) { // 指定构建号
            downloadApi.getSingleBuildHistory(projectId, srcPipelineId, targetBuildNo)
                ?: throw AtomException("pipeline does not have No.$targetBuildNo build")
        } else if (useLatestSuccessBuild) { // 最近成功构建号
            downloadApi.getLatestSuccessBuild(projectId, srcPipelineId)
                ?: throw AtomException("pipeline does not have successful build")
        } else { // 最新构建号
            var targetBuildNum = "-1"
            if (currentPipelineId == srcPipelineId) { // 如果是当前流水线，最新构建号判定为本次执行
                targetBuildNum = currentBuildNum
            }
            downloadApi.getSingleBuildHistory(projectId, srcPipelineId, targetBuildNum)
                ?: throw AtomException("pipeline does not have No.$targetBuildNum build")
        }
        val downloadFileList = downloadFile(
            userId = userId,
            projectId = projectId,
            targetPipelineId = srcPipelineId,
            buildHistory = buildHistory,
            srcPaths = srcPaths,
            destPath = destPath,
            rangeDownloader = rangeDownloader,
            ignoreDigestCheck = ignoreDigestCheck
        )
        if (downloadFileList.isEmpty()) {
            throw AtomException("no file downloaded")
        } else {
            val result = atomContext.result
            result.data = mapOf(
                DOWNLOAD_PIPELINE_FILE_FULLPATH_LIST to StringData(downloadFileList.toString())
            )
        }
    }

    private fun downloadFile(
        userId: String,
        projectId: String,
        targetPipelineId: String,
        buildHistory: BuildHistory,
        srcPaths: List<String>,
        destPath: String,
        rangeDownloader: Boolean,
        ignoreDigestCheck: Boolean
    ): MutableList<String> {
        val downloadFileList = mutableListOf<String>()
        val buildId = buildHistory.id
        logger.info("download file from pipeline:$targetPipelineId, buildNo:${buildHistory.buildNum}, dest path:$destPath")
        try {
            for (workPath in srcPaths) {
                var pathDownloadCount = 0
                val path = workPath.removePrefix("./").removePrefix("/")
                val files = downloadApi.matchFiles(
                    userId,
                    projectId,
                    repoName = REPO_PIPELINE,
                    path = PathUtils.normalizeFullPath("/$targetPipelineId/$buildId/$workPath")
                )
                logger.info(files.size.toString() + " file match, target path: " + destPath)
                for (file in files) {
                    logger.info("start download file: " + file.fullPath)
                    val destFile = File(destPath, File(file.fullPath).name)
                    logger.info("destFile: $destFile")
                    downloadApi.download(
                        userId,
                        projectId,
                        REPO_PIPELINE,
                        file.fullPath,
                        destFile,
                        file.size,
                        rangeDownloader,
                        ignoreDigestCheck
                    )
                    logger.info("download file " + file.fullPath + " done")
                    downloadFileList.add(file.fullPath)
                    pathDownloadCount++
                }
                if (pathDownloadCount == 0) {
                    logger.info("no file match for path: $path")
                }
            }
        } catch (e: Exception) {
            logger.error("download failed error: " + e.message)
            throw AtomException("download failed error: " + e.message)
        }
        return downloadFileList
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DownloadArtifactAtom::class.java)
        private val downloadApi = DownloadApi()
        private const val DOWNLOAD_PIPELINE_FILE_FULLPATH_LIST = "FILE_FULL_PATH_LIST"
    }
}
