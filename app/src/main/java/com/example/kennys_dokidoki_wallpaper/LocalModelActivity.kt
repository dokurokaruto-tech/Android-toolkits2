package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class LocalModelActivity : AppCompatActivity() {

    private lateinit var etModelUrl: EditText
    private lateinit var rvModels: RecyclerView
    private lateinit var adapter: LocalModelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_model)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        etModelUrl = findViewById(R.id.et_model_url)
        // 推奨モデルのURLをセットしておくわ
        etModelUrl.setText("https://huggingface.co/HauhauCS/Gemma-4-E2B-Uncensored-HauhauCS-Aggressive/resolve/main/Gemma-4-E2B-Uncensored-HauhauCS-Aggressive-Q6_K_P.gguf?download=true")

        findViewById<Button>(R.id.btn_download).setOnClickListener {
            val url = etModelUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                val fileName = url.substringAfterLast("/").substringBefore("?")
                LocalModelManager.downloadModel(this, url, fileName)
                Toast.makeText(this, "ダウンロードを開始しました。通知を確認してください。", Toast.LENGTH_SHORT).show()
            }
        }

        rvModels = findViewById(R.id.rv_installed_models)
        rvModels.layoutManager = LinearLayoutManager(this)
        
        refreshModelList()
    }

    override fun onResume() {
        super.onResume()
        refreshModelList()
    }

    private fun refreshModelList() {
        val models = LocalModelManager.getAllModels(this)
        adapter = LocalModelAdapter(
            models,
            onSelectClick = { model ->
                // モデルを選択してハイライト
                LocalModelManager.setSelectedModel(this, model.name)
                
                // 非同期でロードを開始
                lifecycleScope.launch {
                    val error = LlmInferenceEngine.loadModelDetailed(this@LocalModelActivity, model.file)
                    if (error == null) {
                        Toast.makeText(this@LocalModelActivity, "『${model.name}』の準備が完了しました。", Toast.LENGTH_SHORT).show()
                    } else {
                        // 詳細なエラーを表示
                        AlertDialog.Builder(this@LocalModelActivity)
                            .setTitle("ロード失敗")
                            .setMessage(error)
                            .setPositiveButton("わかった", null)
                            .show()
                    }
                    refreshModelList() // ステータス表示を更新
                }
                
                refreshModelList()
            },
            onDeleteClick = { model ->
                AlertDialog.Builder(this)
                    .setTitle("モデルの削除")
                    .setMessage("『${model.name}』を削除してもいい？")
                    .setPositiveButton("削除") { _, _ ->
                        if (LocalModelManager.deleteModel(model)) {
                            // もし削除したモデルが選択中なら、エンジンをアンロード
                            if (LocalModelManager.isSelected(this, model.name)) {
                                LlmInferenceEngine.unloadModel()
                            }
                            Toast.makeText(this, "モデルを削除しました。", Toast.LENGTH_SHORT).show()

                            refreshModelList()
                        }
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            },
            context = this
        )
        rvModels.adapter = adapter
    }

    class LocalModelAdapter(
        private val models: List<LocalModel>,
        private val onSelectClick: (LocalModel) -> Unit,
        private val onDeleteClick: (LocalModel) -> Unit,
        private val context: Context
    ) : RecyclerView.Adapter<LocalModelAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_model_name)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_model)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_local_model, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val model = models[position]
            val isSelected = LocalModelManager.isSelected(context, model.name)

            // モデル名にサイズ情報をつけるわ
            val sizeInMb = model.file.length() / (1024 * 1024)
            val sizeText = if (sizeInMb > 1024) {
                String.format("%.1f GB", sizeInMb / 1024.0)
            } else {
                "${sizeInMb} MB"
            }

            holder.tvName.text = if (isSelected) {
                "✅ ${model.name}\n   ($sizeText) — 使用中"
            } else {
                "📦 ${model.name}\n   ($sizeText)"
            }

            // 選択中のモデルをハイライト
            if (isSelected) {
                holder.itemView.setBackgroundColor(Color.parseColor("#1A00F0FF"))
            } else {
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            }

            // タップでモデルを選択
            holder.itemView.setOnClickListener { onSelectClick(model) }
            holder.btnDelete.setOnClickListener { onDeleteClick(model) }
        }

        override fun getItemCount() = models.size
    }
}
