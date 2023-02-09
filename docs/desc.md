#### 插件功能
本插件将制品库中的文件拉取到构建机上，支持拉取流水线仓库或自定义仓库

#### 插件参数
- 拉取仓库(repoName): 流水线仓库(pipeline)或自定义仓库(custom)
- 流水线(targetPipelineId): 仓库为流水线仓库时必填，选择流水线或输入流水线id
- 构建号(latestBuildNum): 仓库为流水线仓库时必填, 最新构建号为true，指定构建号为false
- 是否提取最近构建成功的文件(isSuccessfulBuild): 构建号为最新构建号时填写
- 指定构建号(buildNum): 构建号为指定构建号时必填，选择或输入构建号
- 待下载文件路径(srcPaths): 文件名支持*通配符，多个路径用英文逗号隔开。流水线仓库只需要填写文件名，例如artifact.zip；自定义仓库需要填写文件完整路径，例如/release/artifact.zip
- 下载文件目录路径(destPath): 下载到本地的路径，只支持相对路径，前面默认拼接工作空间
- 是否开启分片断点下载(rangeDownload): 开启后分片下载文件
- 关闭文件md5值校验(ignoreDigestCheck): 开启分片断点下载后选填，关闭后下载完成后不校验md5