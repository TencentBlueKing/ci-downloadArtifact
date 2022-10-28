package com.tencent.bk.devops.atom.task.pojo

data class PipelineBuildMaterial(
    val aliasName: String?,
    val url: String,
    val branchName: String?,
    val newCommitId: String?,
    val newCommitComment: String?,
    val commitTimes: Int?
)
