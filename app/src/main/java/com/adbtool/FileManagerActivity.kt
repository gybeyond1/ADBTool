package com.adbtool

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adbtool.databinding.ActivityFileManagerBinding
import com.adbtool.databinding.ItemFileBinding
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class FileManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileManagerBinding
    private val files = mutableListOf<AdbManager.FileItem>()
    private lateinit var adapter: FileAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var currentPath = "/sdcard"

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> uploadFile(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "文件管理"

        adapter = FileAdapter()
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = adapter

        binding.btnBack.setOnClickListener { navigateUp() }
        binding.btnUpload.setOnClickListener { openFilePicker() }
        binding.btnNewFolder.setOnClickListener { showNewFolderDialog() }

        loadFiles(currentPath)
    }

    private fun loadFiles(path: String) {
        currentPath = path
        binding.tvPath.text = path
        binding.btnBack.isEnabled = path != "/" && path != "/sdcard"
        thread {
            try {
                val list = AdbManager.listFiles(path)
                handler.post {
                    files.clear()
                    files.addAll(list)
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun navigateUp() {
        val parent = currentPath.substringBeforeLast("/")
        loadFiles(parent.ifEmpty { "/" })
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerLauncher.launch(Intent.createChooser(intent, "选择文件"))
    }

    private fun uploadFile(uri: Uri) {
        val fileName = getFileName(uri) ?: "upload_file"
        val remotePath = if (currentPath.endsWith("/")) "$currentPath$fileName" else "$currentPath/$fileName"
        Toast.makeText(this, "正在上传: $fileName", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val tempFile = File(cacheDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                AdbManager.pushFile(tempFile, remotePath)
                tempFile.delete()
                handler.post {
                    Toast.makeText(this, "上传成功", Toast.LENGTH_SHORT).show()
                    loadFiles(currentPath)
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "上传失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadFile(item: AdbManager.FileItem) {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadDir.exists()) downloadDir.mkdirs()
        val localFile = File(downloadDir, item.name)
        Toast.makeText(this, "正在下载: ${item.name}", Toast.LENGTH_SHORT).show()
        thread {
            try {
                AdbManager.pullFile(item.path, localFile)
                handler.post {
                    Toast.makeText(this, "已保存到: ${localFile.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun deleteFile(item: AdbManager.FileItem) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除 \"${item.name}\" 吗？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                thread {
                    try {
                        AdbManager.deleteFile(item.path)
                        handler.post {
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                            loadFiles(currentPath)
                        }
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNewFolderDialog() {
        val editText = EditText(this).apply { hint = "文件夹名称" }
        AlertDialog.Builder(this)
            .setTitle("新建文件夹")
            .setView(editText)
            .setPositiveButton("创建") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val path = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"
                    thread {
                        try {
                            AdbManager.createDirectory(path)
                            handler.post {
                                Toast.makeText(this, "已创建", Toast.LENGTH_SHORT).show()
                                loadFiles(currentPath)
                            }
                        } catch (e: Exception) {
                            handler.post {
                                Toast.makeText(this, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) {
                    name = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast("/")
        }
        return name
    }

    inner class FileAdapter : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = files[position]
            holder.binding.tvFileName.text = item.name
            if (item.isDirectory) {
                holder.binding.tvFileIcon.text = "📁"
                holder.binding.tvFileInfo.text = "文件夹"
                holder.binding.btnDownload.visibility = android.view.View.GONE
            } else {
                holder.binding.tvFileIcon.text = "📄"
                holder.binding.tvFileInfo.text = formatSize(item.size)
                holder.binding.btnDownload.visibility = android.view.View.VISIBLE
            }
            holder.binding.root.setOnClickListener {
                if (item.isDirectory) loadFiles(item.path)
            }
            holder.binding.btnDownload.setOnClickListener { downloadFile(item) }
            holder.binding.btnDelete.setOnClickListener { deleteFile(item) }
        }

        override fun getItemCount() = files.size

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> "${bytes / (1024 * 1024 * 1024)} GB"
            }
        }
    }
}
