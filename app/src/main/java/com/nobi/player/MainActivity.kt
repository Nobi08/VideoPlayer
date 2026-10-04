package com.nobi.player
import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
  data class V(val name: String, val uri: String, val folder: String)
  private val all = mutableListOf<V>()
  private var cur: String? = null
  private lateinit var lv: ListView

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    lv = ListView(this); setContentView(lv)
    val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) scan() else requestPermissions(arrayOf(p), 1)
  }
  override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
    super.onRequestPermissionsResult(c, p, r)
    if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) scan()
  }
  private fun scan() {
    val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val cols = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
    contentResolver.query(base, cols, null, null, "date_added DESC")?.use { c ->
      while (c.moveToNext()) all += V(c.getString(1) ?: "?", ContentUris.withAppendedId(base, c.getLong(0)).toString(), c.getString(2) ?: "Other")
    }
    show()
  }
  private fun show() {
    val f = cur
    if (f == null) {
      val g = all.groupBy { it.folder }; val names = g.keys.toList()
      lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names.map { "📁 $it (${g[it]!!.size})" })
      lv.setOnItemClickListener { _, _, i, _ -> cur = names[i]; show() }
    } else {
      val vs = all.filter { it.folder == f }
      lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, vs.map { "🎬 ${it.name}" })
      lv.setOnItemClickListener { _, _, i, _ ->
        startActivity(Intent(this, PlayerActivity::class.java).putStringArrayListExtra("uris", ArrayList(vs.map { it.uri })).putExtra("i", i))
      }
    }
  }
  @Deprecated("") override fun onBackPressed() { if (cur != null) { cur = null; show() } else super.onBackPressed() }
}
