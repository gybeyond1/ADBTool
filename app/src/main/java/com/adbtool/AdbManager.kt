package com.adbtool

import com.cgutman.adblib.AdbBase64
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import com.cgutman.adblib.AdbStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AndroidAdbBase64 : AdbBase64 {
    override fun encodeToString(data: ByteArray): String {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    }
}

object AdbManager {
    private var connection: AdbConnection? = null
    private var keyPair: AdbCrypto? = null
    var currentHost: String = ""
    var currentPort: Int = 5555

    val isConnected: Boolean
        get() = connection != null

    @Throws(Exception::class)
    fun connect(host: String, port: Int = 5555, timeout: Int = 15000) {
        if (keyPair == null) {
            keyPair = AdbCrypto.generateAdbKeyPair(AndroidAdbBase64)
        }
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeout)
        val conn = AdbConnection.create(socket, keyPair)
        conn.connect()
        connection = conn
        currentHost = host
        currentPort = port
    }

    fun disconnect() {
        try {
            connection?.close()
        } catch (_: Exception) {}
        connection = null
        currentHost = ""
    }

    @Throws(Exception::class)
    fun shell(command: String): String {
        val conn = connection ?: throw IllegalStateException("未连接设备")
        val stream = conn.open("shell:$command")
        val baos = ByteArrayOutputStream()
        try {
            while (true) {
                val data = stream.read()
                baos.write(data)
            }
        } catch (_: java.io.IOException) {
            // 流关闭时抛出 IOException，表示命令执行完毕
        }
        try { stream.close() } catch (_: Exception) {}
        return baos.toString().trim()
    }

    data class FileItem(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val permissions: String,
        val path: String
    )

    @Throws(Exception::class)
    fun listFiles(path: String): List<FileItem> {
        val output = shell("ls -la \"$path\"")
        val items = mutableListOf<FileItem>()
        val lines = output.lines()
        for (line in lines) {
            if (line.isBlank() || line.startsWith("total")) continue
            val parts = line.split(Regex("\\s+"), limit = 8)
            if (parts.size < 8) continue
            val perms = parts[0]
            val isDir = perms.startsWith("d")
            val size = parts[4].toLongOrNull() ?: 0L
            val name = parts[7]
            if (name == "." || name == "..") continue
            val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
            items.add(FileItem(name, isDir, size, perms, fullPath))
        }
        return items
    }

    @Throws(Exception::class)
    fun deleteFile(path: String): String {
        return shell("rm -rf \"$path\"")
    }

    @Throws(Exception::class)
    fun createDirectory(path: String): String {
        return shell("mkdir -p \"$path\"")
    }

    @Throws(Exception::class)
    fun pullFile(remotePath: String, localFile: File) {
        val conn = connection ?: throw IllegalStateException("未连接设备")
        val sync = conn.open("sync:")

        val nameBytes = remotePath.toByteArray()
        val recvPacket = ByteBuffer.allocate(8 + nameBytes.size)
        recvPacket.order(ByteOrder.LITTLE_ENDIAN)
        recvPacket.put("RECV".toByteArray())
        recvPacket.putInt(nameBytes.size)
        recvPacket.put(nameBytes)
        sync.write(recvPacket.array())

        val fos = FileOutputStream(localFile)
        try {
            while (true) {
                val header = readExact(sync, 8)
                val cmd = String(header, 0, 4)
                val arg = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                when (cmd) {
                    "DATA" -> {
                        val data = readExact(sync, arg)
                        fos.write(data)
                    }
                    "DONE" -> break
                    "FAIL" -> {
                        val errData = readExact(sync, arg)
                        throw Exception("拉取失败: ${String(errData)}")
                    }
                    else -> throw Exception("未知 sync 命令: $cmd")
                }
            }
        } finally {
            fos.close()
            sync.close()
        }
    }

    @Throws(Exception::class)
    fun pushFile(localFile: File, remotePath: String) {
        val conn = connection ?: throw IllegalStateException("未连接设备")
        val sync = conn.open("sync:")

        val nameBytes = "$remotePath,0666".toByteArray()
        val sendPacket = ByteBuffer.allocate(8 + nameBytes.size)
        sendPacket.order(ByteOrder.LITTLE_ENDIAN)
        sendPacket.put("SEND".toByteArray())
        sendPacket.putInt(nameBytes.size)
        sendPacket.put(nameBytes)
        sync.write(sendPacket.array())

        val buffer = ByteArray(64 * 1024)
        val input = localFile.inputStream()
        try {
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                val dataPacket = ByteBuffer.allocate(8 + read)
                dataPacket.order(ByteOrder.LITTLE_ENDIAN)
                dataPacket.put("DATA".toByteArray())
                dataPacket.putInt(read)
                dataPacket.put(buffer, 0, read)
                sync.write(dataPacket.array())
            }
        } finally {
            input.close()
        }

        val donePacket = ByteBuffer.allocate(8)
        donePacket.order(ByteOrder.LITTLE_ENDIAN)
        donePacket.put("DONE".toByteArray())
        donePacket.putInt((System.currentTimeMillis() / 1000).toInt())
        sync.write(donePacket.array())

        val header = readExact(sync, 8)
        val cmd = String(header, 0, 4)
        val arg = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (cmd == "FAIL") {
            val errData = readExact(sync, arg)
            sync.close()
            throw Exception("推送失败: ${String(errData)}")
        }
        sync.close()
    }

    private fun readExact(stream: AdbStream, size: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        var remaining = size
        while (remaining > 0) {
            val chunk = stream.read()
            if (chunk == null || chunk.isEmpty()) break
            baos.write(chunk)
            remaining -= chunk.size
        }
        return baos.toByteArray()
    }

    data class AppItem(
        val packageName: String,
        val isSystem: Boolean,
        val isDisabled: Boolean,
        val label: String = ""
    )

    @Throws(Exception::class)
    fun listApps(filter: String = "all"): List<AppItem> {
        val allOutput = shell("pm list packages")
        val systemOutput = shell("pm list packages -s")
        val disabledOutput = shell("pm list packages -d")

        val systemApps = systemOutput.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
        val disabledApps = disabledOutput.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()

        val apps = mutableListOf<AppItem>()
        for (line in allOutput.lines()) {
            if (line.isBlank()) continue
            val pkg = line.removePrefix("package:").trim()
            if (pkg.isEmpty()) continue
            val isSystem = pkg in systemApps
            val isDisabled = pkg in disabledApps
            when (filter) {
                "third" -> if (isSystem) continue
                "system" -> if (!isSystem) continue
                "disabled" -> if (!isDisabled) continue
            }
            apps.add(AppItem(pkg, isSystem, isDisabled))
        }
        return apps
    }

    @Throws(Exception::class)
    fun installApk(apkPath: String): String {
        val fileName = File(apkPath).name
        val remotePath = "/data/local/tmp/$fileName"
        pushFile(File(apkPath), remotePath)
        val result = shell("pm install -r \"$remotePath\"")
        shell("rm -f \"$remotePath\"")
        return result
    }

    @Throws(Exception::class)
    fun uninstallApp(packageName: String): String {
        return shell("pm uninstall \"$packageName\"")
    }

    @Throws(Exception::class)
    fun disableApp(packageName: String): String {
        return shell("pm disable-user \"$packageName\"")
    }

    @Throws(Exception::class)
    fun enableApp(packageName: String): String {
        return shell("pm enable \"$packageName\"")
    }
}
