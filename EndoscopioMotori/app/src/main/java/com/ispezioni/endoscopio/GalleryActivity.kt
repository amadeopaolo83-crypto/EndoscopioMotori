package com.ispezioni.endoscopio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GalleryActivity : AppCompatActivity() {

    private lateinit var adapter: MediaAdapter
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        emptyText = findViewById(R.id.emptyText)
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        adapter = MediaAdapter { showActions(it) }
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val ctx = applicationContext
        Thread {
            val list = try {
                MediaRepo.list(ctx)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                adapter.submit(list)
                emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun mime(item: MediaRepo.Item) = if (item.isVideo) "video/mp4" else "image/jpeg"

    private fun showActions(item: MediaRepo.Item) {
        val labels = arrayOf(
            getString(R.string.action_open),
            getString(R.string.action_share),
            getString(R.string.action_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> open(item)
                    1 -> share(item)
                    2 -> confirmDelete(item)
                }
            }
            .show()
    }

    private fun open(item: MediaRepo.Item) {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, mime(item))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.msg_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(item: MediaRepo.Item) {
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime(item))
            .putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, item.name))
    }

    private fun confirmDelete(item: MediaRepo.Item) {
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.yes) { _, _ ->
                val ok = MediaRepo.delete(this, item)
                Toast.makeText(
                    this,
                    if (ok) R.string.msg_deleted else R.string.msg_delete_error,
                    Toast.LENGTH_SHORT
                ).show()
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
