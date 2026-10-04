package com.nobi.player
import android.Manifest
import android.app.KeyguardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
  data class V(val name: String, val uri: String, val folder: String)
  private val all = mutableListOf<V>()
  private var cur: String? = null
  private lateinit var lv: ListView
  private var unlocked = false
  private var asking = false
  private var skip = false
  private val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
  private val auth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    asking = false
    if (r.resultCode == RESULT_OK) unlock() else finish()
  }

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    lv = ListView(this); lv.visibility = View.INVISIBLE; setContentView(lv)
  }
  override fun onStart() {
    super.onStart()
    if (!unlocked && !skip && !asking) lock()
    skip = false
  }
  override fun onStop() {
    super.onStop()
    if (!skip && !asking) unlocked = false
  }
  private fun lock() {
    val kg = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
    val i = if (kg.isDeviceSecure) kg.createConfirmDeviceCredentialIntent("Unlock Player", null) else null
    if (i == null) { unlock(); return }
    asking = true; lv.visibility = View.INVISIBLE; auth.launch(i)
  }
  private fun unlock() {
    unlocked = true; lv.visibility = View.VISIBLE
    if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) scan() else requestPermissions(arrayOf(perm), 1)
  }
  override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
    super.onRequestPermissionsResult(c, p, r)
    if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) scan()
  }
  private fun scan() {
    all.clear()
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
        skip = true
        startActivity(Intent(this, PlayerActivity::class.java).putStringArrayListExtra("uris", ArrayList(vs.map { it.uri })).putExtra("i", i).putStringArrayListExtra("names", ArrayList(vs.map { it.name })))
      }
    }
  }
  @Deprecated("") override fun onBackPressed() { if (cur != null) { cur = null; show() } else super.onBackPressed() }
}
