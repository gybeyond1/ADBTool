package com.adbtool

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adbtool.databinding.ActivityMainBinding
import com.adbtool.databinding.ItemDeviceBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val devices = mutableListOf<String>()
    private lateinit var adapter: DeviceAdapter
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = DeviceAdapter()
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnConnect.setOnClickListener { manualConnect() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnFileManager.setOnClickListener {
            startActivity(Intent(this, FileManagerActivity::class.java))
        }
        binding.btnAppManager.setOnClickListener {
            startActivity(Intent(this, AppManagerActivity::class.java))
        }

        updateConnectedUI()
    }

    override fun onResume() {
        super.onResume()
        updateConnectedUI()
    }

    private fun startScan() {
        devices.clear()
        adapter.notifyDataSetChanged()
        binding.progressScan.visibility = android.view.View.VISIBLE
        binding.progressScan.progress = 0
        binding.tvScanStatus.text = "扫描中..."
        binding.btnScan.isEnabled = false

        thread {
            try {
                LanScanner.scan(
                    context = this,
                    port = 5555,
                    timeout = 400,
                    onProgress = { current, total ->
                        handler.post {
                            binding.progressScan.progress = current
                            binding.tvScanStatus.text = "扫描中... $current/$total"
                        }
                    },
                    onFound = { ip ->
                        handler.post {
                            if (!devices.contains(ip)) {
                                devices.add(ip)
                                adapter.notifyItemInserted(devices.size - 1)
                            }
                        }
                    }
                )
                handler.post {
                    binding.progressScan.visibility = android.view.View.GONE
                    binding.btnScan.isEnabled = true
                    if (devices.isEmpty()) {
                        binding.tvScanStatus.text = "未找到设备，请确保设备已开启无线调试（端口5555）"
                    } else {
                        binding.tvScanStatus.text = "找到 ${devices.size} 台设备"
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    binding.progressScan.visibility = android.view.View.GONE
                    binding.btnScan.isEnabled = true
                    binding.tvScanStatus.text = "扫描失败: ${e.message}"
                }
            }
        }
    }

    private fun manualConnect() {
        val ip = binding.etIp.text.toString().trim()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 5555
        if (ip.isEmpty()) {
            Toast.makeText(this, "请输入IP地址", Toast.LENGTH_SHORT).show()
            return
        }
        connectDevice(ip, port)
    }

    private fun connectDevice(ip: String, port: Int = 5555) {
        binding.btnConnect.isEnabled = false
        thread {
            try {
                AdbManager.connect(ip, port)
                handler.post {
                    binding.btnConnect.isEnabled = true
                    Toast.makeText(this, "连接成功: $ip", Toast.LENGTH_SHORT).show()
                    updateConnectedUI()
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                handler.post {
                    binding.btnConnect.isEnabled = true
                    Toast.makeText(this, "连接失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun disconnect() {
        AdbManager.disconnect()
        updateConnectedUI()
        adapter.notifyDataSetChanged()
        Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
    }

    private fun updateConnectedUI() {
        if (AdbManager.isConnected) {
            binding.llConnected.visibility = android.view.View.VISIBLE
            binding.tvConnectedInfo.text = "已连接: ${AdbManager.currentHost}:${AdbManager.currentPort}"
        } else {
            binding.llConnected.visibility = android.view.View.GONE
        }
    }

    inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ip = devices[position]
            val isCurrent = AdbManager.isConnected && AdbManager.currentHost == ip
            holder.binding.tvDeviceIp.text = ip
            if (isCurrent) {
                holder.binding.tvDeviceStatus.text = "已连接"
                holder.binding.tvDeviceStatus.setTextColor(getColor(R.color.green))
                holder.binding.btnDeviceAction.text = "断开"
            } else {
                holder.binding.tvDeviceStatus.text = ""
                holder.binding.btnDeviceAction.text = "连接"
            }
            holder.binding.btnDeviceAction.setOnClickListener {
                if (isCurrent) {
                    disconnect()
                } else {
                    connectDevice(ip)
                }
            }
        }

        override fun getItemCount() = devices.size
    }
}
