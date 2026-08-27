package com.adbtool

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adbtool.databinding.ActivityAppManagerBinding
import com.adbtool.databinding.ItemAppBinding
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class AppManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppManagerBinding
    private val allApps = mutableListOf<AdbManager.AppItem>()
    private val filteredApps = mutableListOf<AdbManager.AppItem>()
    private lateinit var adapter: AppAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var currentFilter = "all"
    private var searchKeyword = ""

    private val apkPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> installApk(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "应用管理"

        adapter = AppAdapter()
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter

        binding.btnInstall.setOnClickListener { openApkPicker() }
        binding.btnFilterAll.setOnClickListener { setFilter("all") }
        binding.btnFilterThird.setOnClickListener { setFilter("third") }
        binding.btnFilterSystem.setOnClickListener { setFilter("system") }
        binding.btnFilterDisabled.setOnClickListener { setFilter("disabled") }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchKeyword = s?.toString()?.trim()?.lowercase() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadApps()
    }

    private fun loadApps() {
        Toast.makeText(this, "正在加载应用列表...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val apps = AdbManager.listApps("all")
                handler.post {
                    allApps.clear()
                    allApps.addAll(apps)
                    applyFilter()
                    Toast.makeText(this, "加载完成，共 ${apps.size} 个应用", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "加载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setFilter(filter: String) {
        currentFilter = filter
        updateFilterButtons()
        applyFilter()
    }

    private fun updateFilterButtons() {
        val buttons = mapOf(
            "all" to binding.btnFilterAll,
            "third" to binding.btnFilterThird,
            "system" to binding.btnFilterSystem,
            "disabled" to binding.btnFilterDisabled
        )
        for ((key, btn) in buttons) {
            if (key == currentFilter) {
                btn.setBackgroundColor(getColor(R.color.purple_500))
                btn.setTextColor(getColor(R.color.white))
            } else {
                btn.setBackgroundColor(getColor(R.color.gray_light))
                btn.setTextColor(getColor(R.color.black))
            }
        }
    }

    private fun applyFilter() {
        filteredApps.clear()
        for (app in allApps) {
            val matchFilter = when (currentFilter) {
                "third" -> !app.isSystem
                "system" -> app.isSystem
                "disabled" -> app.isDisabled
                else -> true
            }
            val matchSearch = searchKeyword.isEmpty() || app.packageName.lowercase().contains(searchKeyword)
            if (matchFilter && matchSearch) {
                filteredApps.add(app)
            }
        }
        filteredApps.sortBy { it.packageName.lowercase() }
        adapter.notifyDataSetChanged()
        binding.tvAppCount.text = "显示 ${filteredApps.size} / ${allApps.size} 个应用"
    }

    private fun openApkPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/vnd.android.package-archive"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        apkPickerLauncher.launch(Intent.createChooser(intent, "选择APK文件"))
    }

    private fun installApk(uri: Uri) {
        val fileName = getFileName(uri) ?: "app.apk"
        Toast.makeText(this, "正在安装: $fileName", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val tempFile = File(cacheDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val result = AdbManager.installApk(tempFile.absolutePath)
                tempFile.delete()
                handler.post {
                    if (result.contains("Success", ignoreCase = true)) {
                        Toast.makeText(this, "安装成功", Toast.LENGTH_SHORT).show()
                        loadApps()
                    } else {
                        Toast.makeText(this, "安装结果: $result", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "安装失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun uninstallApp(app: AdbManager.AppItem) {
        AlertDialog.Builder(this)
            .setTitle("确认卸载")
            .setMessage("确定要卸载 \"${app.packageName}\" 吗？此操作不可恢复。")
            .setPositiveButton("卸载") { _, _ ->
                thread {
                    try {
                        val result = AdbManager.uninstallApp(app.packageName)
                        handler.post {
                            Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                            loadApps()
                        }
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(this, "卸载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleApp(app: AdbManager.AppItem) {
        val action = if (app.isDisabled) "启用" else "停用"
        AlertDialog.Builder(this)
            .setTitle("确认$action")
            .setMessage("确定要$action \"${app.packageName}\" 吗？")
            .setPositiveButton(action) { _, _ ->
                thread {
                    try {
                        val result = if (app.isDisabled) {
                            AdbManager.enableApp(app.packageName)
                        } else {
                            AdbManager.disableApp(app.packageName)
                        }
                        handler.post {
                            Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                            loadApps()
                        }
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(this, "${action}失败: ${e.message}", Toast.LENGTH_SHORT).show()
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

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = filteredApps[position]
            holder.binding.tvPackageName.text = app.packageName

            val typeBuilder = StringBuilder()
            if (app.isSystem) typeBuilder.append("系统应用") else typeBuilder.append("第三方应用")
            if (app.isDisabled) {
                typeBuilder.append(" · 已停用")
                holder.binding.tvAppType.setTextColor(getColor(R.color.red))
            } else {
                holder.binding.tvAppType.setTextColor(getColor(R.color.gray_dark))
            }
            holder.binding.tvAppType.text = typeBuilder.toString()
            holder.binding.tvAppIcon.text = if (app.isDisabled) "⏸" else "📱"
            holder.binding.btnToggle.text = if (app.isDisabled) "启用" else "停用"
            holder.binding.btnToggle.setOnClickListener { toggleApp(app) }
            holder.binding.btnUninstall.setOnClickListener { uninstallApp(app) }
        }

        override fun getItemCount() = filteredApps.size
    }
}
