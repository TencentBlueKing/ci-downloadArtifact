#### Plugin function
Pull the components of the assembly line warehouse and custom warehouse.

#### Plugin parameters
- Pull repository(repoName):
    - Assembly line warehouse(pipeline)
    - Custom warehouse(custom)
- Pipeline(targetPipelineId):
    - Required when the warehouse is an assembly line warehouse, select an assembly line or enter an assembly line id.
- Build number(latestBuildNum):
    - Required when the warehouse is an assembly line warehouse, the latest build number is true, and the specified build number is false.
- Whether to extract the most recently successfully built files(isSuccessfulBuild):
    - Fill in when the build number is the latest build number
- Specify build number(buildNum):
    - Build number is required when specifying a build number, select or enter a build number.
- The path of the file to be downloaded(srcPaths):
    - The file name supports * wildcards, and multiple paths are separated by English commas.
    - The pipeline warehouse only needs to fill in the file name, such as artifact.zip.
    - Custom warehouse needs to fill in the full path of the file, for example /release/artifact.zip.
- Download file directory path(destPath):
    - The path to download to the local, only supports relative paths, the previous default splicing workspace.
- Whether to enable segment breakpoint download(rangeDownload):
    - After opening, download files in pieces.
- Close file md5 value verification(ignoreDigestCheck):
    - It is optional after opening the segment breakpoint download, after closing it, the md5 will not be verified after the download is completed.