package com.tencent.bk.devops.atom.task

import com.tencent.bk.devops.atom.pojo.AtomBaseParam
import lombok.Data
import lombok.EqualsAndHashCode

@Data
@EqualsAndHashCode(callSuper = true)
class DownloadArtifactParam : AtomBaseParam() {
    val repoName: String = "pipeline"
    val targetPipelineId: String = ""
    val latestBuildNum: Boolean = true
    val isSuccessfulBuild: Boolean = false
    val buildNum: String = ""
    val srcPaths: String = ""
    val destPath: String = ""
    val rangeDownload: Boolean = false
    val ignoreDigestCheck: Boolean = false
}
