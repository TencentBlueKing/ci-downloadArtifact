# 拉取构件
将制品库中的文件拉取到构建机上，支持拉取流水线仓库或自定义仓库

## 使用指南
### 一、新增插件
在蓝盾的研发商店->工作台->新增插件 页面

![添加插件](images/addPlugin.png)

各字段值填写如下:

名称: 拉取构件（这个可以自定义)

标识: downloadArtifact

调试项目: 选择自己的项目

开发语言: java

自定义前端: 否

### 二、发布管理
新增插件后，就会跳转到插件发布管理界面,点击"上架”

![上架插件](images/publish.png)

### 三、上架插件
步骤:

1.上传插件图标,插件图标可以直接使用[downloadArtifact](images/downloadArtifact.png)

2.插件job类型,linux、macos、windows都选上

3.上传插件包，插件包从[releases](https://github.com/TencentBlueKing/ci-downloadArtifact/releases)下载最新版本插件zip包

4.填写发布日志

### 四、插件配置
[插件配置](docs/desc.md)