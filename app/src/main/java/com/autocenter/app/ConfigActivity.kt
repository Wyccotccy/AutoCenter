package com.autocenter.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.autocenter.app.data.TemplateInfo
import com.autocenter.app.databinding.ActivityConfigBinding
import com.autocenter.app.databinding.ItemTemplateBinding
import com.autocenter.app.ui.ConfigViewModel
import java.io.File

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding
    private lateinit var viewModel: ConfigViewModel
    private lateinit var adapter: TemplateAdapter

    /** 相册选图（添加新模板） */
    private val addImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val success = viewModel.addTemplate(uri)
            if (!success) {
                Toast.makeText(this, R.string.template_limit_warning, Toast.LENGTH_SHORT).show()
            }
            // 通知 capture service 重新加载模板
            ScreenCaptureServiceConnection.serviceInstance?.let { svc ->
                val templates = viewModel.templateManager.loadActiveRuntimeTemplates()
                svc.matcherEngine.updateTemplates(templates)
            }
        }
    }

    companion object {
        const val EXTRA_ADD_TEMPLATE_URI = "add_template_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[ConfigViewModel::class.java]

        setupRecyclerView()
        setupViews()

        // 如果从 MainActivity 传入了模板 URI，直接添加
        val templateUri = intent.getStringExtra(EXTRA_ADD_TEMPLATE_URI)
        if (templateUri != null) {
            val uri = Uri.parse(templateUri)
            viewModel.addTemplate(uri)
        }
    }

    private fun setupRecyclerView() {
        adapter = TemplateAdapter(
            templates = emptyList(),
            onToggle = { info -> viewModel.toggleEnabled(info) },
            onMoreClick = { info -> showTemplateMenu(info) }
        )

        binding.recyclerTemplates.layoutManager = LinearLayoutManager(this)
        binding.recyclerTemplates.adapter = adapter

        viewModel.templates.observe(this) { templates ->
            adapter.updateTemplates(templates)
        }
    }

    private fun setupViews() {
        // 返回
        binding.btnBack.setOnClickListener { finish() }

        // 添加模板
        binding.btnAddTemplate.setOnClickListener {
            addImageLauncher.launch("image/*")
        }

        // 灵敏度滑动条
        binding.seekSensitivity.progress = viewModel.matchSensitivity.value ?: 75
        binding.tvSensitivityValue.text = "${binding.seekSensitivity.progress}"
        binding.seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                binding.tvSensitivityValue.text = "$v"
                viewModel.saveSensitivity(v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 降采样
        val dsValues = listOf(25, 50, 75, 100)
        val dsIdx = dsValues.indexOf(viewModel.downsamplePercent.value ?: 50).coerceAtLeast(0)
        binding.spinnerDownsample.setSelection(dsIdx)
        binding.spinnerDownsample.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                viewModel.saveDownsample(dsValues[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // FPS
        val fpsValues = listOf(5, 10, 15, 20, 30)
        val fpsIdx = fpsValues.indexOf(viewModel.fps.value ?: 10).coerceAtLeast(0)
        binding.spinnerFps.setSelection(fpsIdx)
        binding.spinnerFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                viewModel.saveFps(fpsValues[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 最大活跃模板
        val activeValues = listOf(1, 2, 3, 4, 5, 10, 15)
        val activeIdx = activeValues.indexOf(viewModel.maxActive.value ?: 5).coerceAtLeast(0)
        binding.spinnerMaxActive.setSelection(activeIdx)
        binding.spinnerMaxActive.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                viewModel.saveMaxActive(activeValues[pos])
                // 通知 service 重新加载模板
                ScreenCaptureServiceConnection.serviceInstance?.let { svc ->
                    val templates = viewModel.templateManager.loadActiveRuntimeTemplates()
                    svc.matcherEngine.updateTemplates(templates)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 算法
        val algoList = listOf("ORB", "AKAZE")
        val algoIdx = algoList.indexOf(viewModel.algorithm.value ?: "ORB").coerceAtLeast(0)
        binding.spinnerAlgorithm.setSelection(algoIdx)
        binding.spinnerAlgorithm.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                viewModel.saveAlgorithm(algoList[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 校准值
        viewModel.calibX.observe(this) { v ->
            binding.tvCalibX.text = "%.2f".format(v)
        }
        viewModel.calibY.observe(this) { v ->
            binding.tvCalibY.text = "%.2f".format(v)
        }

        binding.btnResetCalib.setOnClickListener {
            viewModel.resetCalibration()
            Toast.makeText(this, "校准值已重置", Toast.LENGTH_SHORT).show()
        }

        binding.btnRecalibrate.setOnClickListener {
            Toast.makeText(this, "请在主界面点击「滑动校准」按钮", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** 长按/点击更多弹出菜单 */
    private fun showTemplateMenu(info: TemplateInfo) {
        val items = mutableListOf<String>().apply {
            add(getString(R.string.menu_edit_name))
            if (!info.isDefault) add(getString(R.string.menu_set_default))
            add(if (info.isEnabled) getString(R.string.menu_disable) else getString(R.string.menu_enable))
            add(getString(R.string.menu_delete))
        }

        AlertDialog.Builder(this)
            .setTitle(info.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    getString(R.string.menu_edit_name) -> showRenameDialog(info)
                    getString(R.string.menu_set_default) -> {
                        viewModel.setAsDefault(info)
                        Toast.makeText(this, "已设为默认", Toast.LENGTH_SHORT).show()
                    }
                    getString(R.string.menu_disable), getString(R.string.menu_enable) -> {
                        viewModel.toggleEnabled(info)
                    }
                    getString(R.string.menu_delete) -> showDeleteDialog(info)
                }
            }
            .show()
    }

    private fun showRenameDialog(info: TemplateInfo) {
        val input = EditText(this).apply {
            setText(info.name)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_edit_name_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.renameTemplate(info, name)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showDeleteDialog(info: TemplateInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_confirm_delete)
            .setMessage(getString(R.string.dialog_confirm_delete))
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                viewModel.deleteTemplate(info)
                // 通知 service
                ScreenCaptureServiceConnection.serviceInstance?.let { svc ->
                    val templates = viewModel.templateManager.loadActiveRuntimeTemplates()
                    svc.matcherEngine.updateTemplates(templates)
                }
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}

// region RecyclerView Adapter

class TemplateAdapter(
    private var templates: List<TemplateInfo>,
    private val onToggle: (TemplateInfo) -> Unit,
    private val onMoreClick: (TemplateInfo) -> Unit
) : RecyclerView.Adapter<TemplateAdapter.ViewHolder>() {

    fun updateTemplates(list: List<TemplateInfo>) {
        templates = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTemplateBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(templates[position])
    }

    override fun getItemCount(): Int = templates.size

    inner class ViewHolder(private val binding: ItemTemplateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(info: TemplateInfo) {
            binding.tvName.text = info.name
            binding.chkEnabled.isChecked = info.isEnabled
            binding.tvInfo.text = when {
                info.isDefault -> "默认模板"
                else -> "已${if (info.isEnabled) "启用" else "禁用"}"
            }

            // 加载缩略图
            val file = File(binding.root.context.filesDir, "templates/${info.fileName}")
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                binding.ivThumbnail.setImageBitmap(bmp)
            } else {
                binding.ivThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            binding.chkEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(info.copy(isEnabled = isChecked))
            }

            binding.btnMore.setOnClickListener { onMoreClick(info) }

            // 整行点击 = 编辑名称
            binding.root.setOnClickListener { onMoreClick(info) }
        }
    }
}

// endregion
