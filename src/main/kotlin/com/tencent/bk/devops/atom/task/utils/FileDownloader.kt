package com.tencent.bk.devops.atom.task.utils

import com.tencent.bk.devops.atom.exception.AtomException
import com.tencent.bk.devops.plugin.utils.MachineEnvUtils
import com.tencent.bkrepo.common.artifact.hash.md5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.LongAdder

class FileDownloader(
    private val request: Request,
    destFile: File,
    private val ignoreDigestCheck: Boolean = false
) {

    private var parallelNum: Int = 2
    private var destFile: File
    private var tempFile: File // 用来存储每个协程下载的进度的临时文件
    private var parallelLen: Long = 0 // 每个协程要下载的长度
    private val totalFinish: LongAdder = LongAdder() // 总共完成了多少
    private var totalLen: Long = 0 // 服务端文件总长度
    private var begin: Long = 0
    private var md5: String? = ""
    private var runningNum: LongAdder = LongAdder()

    fun downloadFile() {
        logger.info("start to download...")
        begin = System.currentTimeMillis()
        initParameter()
        initFile()
        run()
        checkDownloadStatus()
    }

    /**
     * 读取指定URl的"Content-Length"头
     * 因为只需要读取Header所以发送"HEAD"请求即可
     */
    private fun getContentLength(): FileData {
        try {
            val response = Jsoup.connect(request.url.toString())
                .headers(request.headers.toMap())
                .ignoreContentType(true)
                .method(Connection.Method.HEAD)
                .execute()
            validateStatusCode(response)
            return FileData(response.header("X-Checksum-Md5"), response.header("Content-Length")!!.toLong())
        } catch (e: Exception) {
            logger.error("error is error[${request.url}], cause: ${e.message}")
            throw AtomException("get content length error")
        }
    }

    private fun initParameter() {
        val fileData = getContentLength()
        totalFinish.reset()
        totalLen = fileData.length
        md5 = fileData.md5
        logger.info("file size: $totalLen")
        parallelNum = when (totalLen) {
            in 0..MB_SIZE -> DEFAULT_NUM
            in MB_SIZE + 1..GB_SIZE -> MEDIAN_NUM
            else -> LARGEST_NUM
        }
        parallelLen = (totalLen + parallelNum - 1) / parallelNum // 计算每个协程要下载的长度
    }

    /**
     * 初始化目标文件和临时文件（用于断点下载）
     */
    private fun initFile() {
        if (!destFile.parentFile.exists()) destFile.parentFile.mkdirs()
        if (!destFile.exists()) {
            val raf = RandomAccessFile(destFile, "rw")
            raf.setLength(totalLen)
            raf.close()
        }
        if (!tempFile.exists()) {
            val raf = RandomAccessFile(tempFile, "rw")
            repeat(parallelNum) {
                raf.writeLong(0) // 写入每个协程的开始位置(都是从0开始)
            }
            raf.close()
        }
    }

    /**
     * 执行协程并发下载
     */
    private fun run() {
        runBlocking(Dispatchers.IO) {
            launch {
                repeat(parallelNum) {
                    Thread.sleep(100)
                    async {
                        if (MachineEnvUtils.getOS() == MachineEnvUtils.OSType.WINDOWS &&
                            LARGEST_NUM == parallelNum &&
                            !destFile.path.startsWith("c:\\", true)
                        ) {
                            var flag = false
                            while (!flag) {
                                if (runningNum.sum() < parallelNum / 2) {
                                    downloadFileWithRange(it)
                                    flag = true
                                }
                                Thread.sleep(100)
                            }
                        } else {
                            downloadFileWithRange(it)
                        }
                    }
                }
            }
        }
    }

    /**
     * 文件下载
     */
    private fun downloadFileWithRange(id: Int) {
        try {
            runningNum.increment()
            val tempRaf = RandomAccessFile(tempFile, "rw")
            tempRaf.seek((id * BYTE_SIZE).toLong()) // 将指针移动到当前协程的位置(每个线程写1个long值, 共占8个字节)
            tempRaf.use {
                val corFinish: Long = tempRaf.readLong() // 读取当前线程已完成了多少
                totalFinish.add(corFinish) // 统计所有协程总共完成了多少
                val start: Long = id * parallelLen + corFinish
                val end: Long = if (id == parallelNum - 1) {
                    totalLen - 1
                } else {
                    id * parallelLen + parallelLen - 1
                }
                getAndWriteStream(id, start, end, corFinish, tempRaf)
            }
        } catch (e: Exception) {
            logger.error("error is error[${request.url}], cause: ${e.message}")
            throw AtomException("download file with range error: cause: ${e.message}")
        } finally {
            runningNum.decrement()
        }
    }

    private fun getAndWriteStream(
        id: Int,
        start: Long,
        end: Long,
        corFinish: Long,
        tempRaf: RandomAccessFile
    ) {
        logger.info("parallel :$id, start : $start -- end :$end")
        var flag = false
        var tempEnd: Long = start + Int.MAX_VALUE
        var tempStart: Long = start
        while (!flag) {
            if (tempEnd > end) {
                tempEnd = end
                flag = true
            }
            logger.info("parallel :$id, will get stream from $tempStart to $tempEnd")
            val inputStream = getRangeInputStream(tempStart, tempEnd)
            val output = RandomAccessFile(destFile, "rw")
            output.seek(tempStart) // 设置当前线程保存数据的位置
            inputStream.use {
                writeStream(id, output, inputStream, corFinish, tempRaf)
            }
            tempStart = tempEnd
            tempEnd += Int.MAX_VALUE
        }
    }

    private fun writeStream(
        id: Int,
        output: RandomAccessFile,
        input: InputStream,
        corFinish: Long,
        tempRaf: RandomAccessFile
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var len: Int
        var logTime = System.currentTimeMillis()
        var corFinLen: Long = corFinish
        output.use { it ->
            while (input.read(buffer).also { len = it } != -1) {
                it.write(buffer, 0, len)
                corFinLen += len.toLong() // 每次写入数据之后, 统计当前协程完成了多少
                tempRaf.seek((id * BYTE_SIZE).toLong())
                tempRaf.writeLong(corFinLen) // 将当前协程完成了多少写入到临时文件
                totalFinish.add(len.toLong())
                val now = System.currentTimeMillis()
                if ((now - logTime) > LOG_DURATION) {
                    val percent = String.format(Locale.ENGLISH, "%.1f", totalFinish.sum() / totalLen.toDouble() * 100)
                    logger.info("parallel :$id, currentTime: $now $destFile >>> $percent%\n")
                    logTime = now
                }
            }
        }
    }

    private fun checkDownloadStatus() {
        logger.info("totalFinish ${totalFinish.sum()},totalLen is $totalLen")
        if (totalFinish.sum() != totalLen) {
            throw AtomException("File that downloaded was corrupted, cause of the length is not correct")
        }
        val timeCost = String.format(Locale.ENGLISH, "%.2f", (System.currentTimeMillis() - begin) / 1000.0)
        logger.info("file transfer 100%, time: $timeCost second(s)")
        if (ignoreDigestCheck) return
        val newMd5 = destFile.md5()
        logger.info("md5: $md5  ---- $newMd5")
        if (md5.equals(newMd5)) {
            tempFile.delete() // 删除临时文件
        } else {
            logger.info("file is corrupted!")
            destFile.delete()
            tempFile.delete() // 删除临时文件
            throw AtomException("File that downloaded was corrupted!")
        }
    }

    /**
     * 清楚文件
     */
    fun removeFiles() {
        if (destFile.exists()) {
            logger.info("try to remove dest file")
            destFile.delete()
        }
        if (tempFile.exists()) {
            logger.info("try to remove temp file")
            tempFile.delete()
        }
        totalFinish.reset()
        runningNum.reset()
    }

    /**
     * 获取指定区间输入流
     * 注意超时时间长一些
     */
    private fun getRangeInputStream(start: Long, end: Long): InputStream {
        try {
            return Jsoup
                .connect(request.url.toString())
                .ignoreContentType(true)
                .maxBodySize(Integer.MAX_VALUE)
                .headers(request.headers.toMap())
                .header("Range", "bytes=$start-$end")
                .timeout(0)
                .execute()
                .bodyStream()
        } catch (e: Exception) {
            logger.error("error is from getting range data [${request.url}], cause: ${e.message}")
            throw AtomException("get range stream error")
        }
    }

    private fun validateStatusCode(response: Connection.Response) {
        val statusCode = response.statusCode()
        if (statusCode != 200) {
            val resp = response.body()
            logger.info(
                "http request response code: $statusCode, responseContent: $resp"
            )
            if (statusCode in 400..499) {
                throw AtomException("http request client error")
            }
            if (statusCode in 500..599) {
                throw AtomException("http request server error")
            }
        }
    }

    data class FileData(
        val md5: String?,
        val length: Long
    )

    init {
        this.destFile = destFile
        this.tempFile = File(this.destFile.absolutePath + ".temp")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FileDownloader::class.java)
        private const val BUFFER_SIZE = 1024 * 1024
        private const val BYTE_SIZE = 8
        private const val LOG_DURATION = 3000
        private const val BASE_SIZE = 1024 * 1024
        private const val MB_SIZE = 100 * BASE_SIZE
        private const val GB_SIZE = 1024 * BASE_SIZE
        private const val DEFAULT_NUM = 1
        private const val MEDIAN_NUM = 2
        private const val LARGEST_NUM = 4
    }
}
