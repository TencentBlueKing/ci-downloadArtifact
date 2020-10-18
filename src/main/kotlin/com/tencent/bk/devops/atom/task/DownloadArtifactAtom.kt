package com.tencent.bk.devops.atom.task

import com.tencent.bk.devops.atom.AtomContext
import com.tencent.bk.devops.atom.common.Status
import com.tencent.bk.devops.atom.spi.AtomService
import com.tencent.bk.devops.atom.spi.TaskAtom
import org.slf4j.LoggerFactory
import java.io.File

@AtomService(paramClass = DownloadArtifactParam::class)
class DownloadArtifactAtom : TaskAtom<DownloadArtifactParam> {
    override fun execute(atomContext: AtomContext<DownloadArtifactParam>) {
        val atomParam = atomContext.param
        val atomResult = atomContext.result
        checkAndInitParam(atomParam)

        val srcPath = atomParam.srcPath
        val destPath = atomParam.destPath
        val workspace = atomParam.bkWorkspace
        val projectId: String = atomParam.projectName
        val userId = atomParam.pipelineStartUserName
//        val isParallel = atomParam.isParallel == "true"

        var count = 0
        for (pattern in srcPath.split(",").filter { it.isNotBlank() }) {
            val path = pattern.removePrefix("./").removePrefix("/")
            var singCount = 0
            var files: List<FileInfo> = archiveApi.matchFile(userId, projectId, path, "")
            for (file in files) {
                archiveApi.downloadCustomFile(userId, projectId, file.path, File(destPath, file.name), file.size)
                singCount++
            }
            if (singCount == 0) {
                logger.info("the $path file can not be matched successfully!........ ")
            } else {
                count++
            }
        }
        if (count == 0) {
            atomResult.setStatus(Status.error)
            atomResult.setMessage("0 file found in path: $srcPath")
        }
    }

    private fun checkAndInitParam(atomParam: DownloadArtifactParam) {
//        if (atomParam.reportName.isNullOrBlank()) {
//            throw AtomException("invalid reportName")
//        }
//        if (atomParam.indexFile.isNullOrBlank()) {
//            throw AtomException("invalid indexFile")
//        }
//        if (atomParam.fileDir.isNullOrBlank()) {
//            throw AtomException("invalid fileDir")
//        }
//
//        var indexFileCharset = atomParam.indexFileCharset
//        if (indexFileCharset.isNullOrBlank()) {
//            indexFileCharset = "UTF-8"
//        }
//        if (indexFileCharset == "default") {
//            indexFileCharset = Charset.defaultCharset().name()
//        }
//        if (!Charset.availableCharsets().containsKey(indexFileCharset)) {
//            throw RuntimeException("unsupported charset: $indexFileCharset")
//        }
//        atomParam.indexFileCharset = indexFileCharset
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DownloadArtifactAtom::class.java)
        var archiveApi = ArchiveApi()
    }
}