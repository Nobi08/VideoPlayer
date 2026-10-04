package com.nobi.player
import android.Manifest
import android.app.KeyguardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
  data class V(val id: Long, val name: String, val uri: String, val folder: String, val dur: Long, val size: Long, val date: Long)
  data class Row(val title: String, val sub: String, val v: V?, val click: () -> Unit)
  private val all = mutableListOf<V>()
  private var rows = listOf<Row>()
  private var cur: String? = null
  private var sort = 0
  private var q = ""
  private lateinit var lv: ListView
  private lateinit var box: LinearLayout
  private lateinit var sortBtn: TextView
  private var unlocked = false
  private var asking = false
  private var skip = false
  private val sortNames = arrayOf("Date", "Name", "Size", "Length")
  private val cache = LruCache<Long, Bitmap>(120)
  private val pool = Executors.newFixedThreadPool(3)
  private val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
  private val auth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    asking = false
    if (r.resultCode == RESULT_OK) unlock() else finish()
  }
  private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()
  private fun fmt(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
  }
  private fun mb(b: Long): String = if (b >= (1L shl 30)) "%.1f GB".format(b / 1073741824.0) else "%d MB".format(b shr 20)
  private fun thumb(v: V): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(Uri.parse(v.uri), Size(256, 144), null)
    else MediaStore.Video.Thumbnails.getThumbnail(contentResolver, v.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
  } catch (e: Exception) { null }

  private val adapter = object : BaseAdapter() {
    override fun getCount() = rows.size
    override fun getItem(pos: Int): Any = rows[pos]
    override fun getItemId(pos: Int) = pos.toLong()
    override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
      val r = rows[pos]
      val ctx = this@MainActivity
      val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
      val v = r.v
      if (v != null) {
        val th = ImageView(ctx).apply {
          scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF222222.toInt())
          layoutParams = LinearLayout.LayoutParams(dp(112), dp(63))
        }
        th.tag = v.id
        val b = cache.get(v.id)
        if (b != null) th.setImageBitmap(b) else pool.execute {
          val bm = thumb(v)
          if (bm != null) { cache.put(v.id, bm); runOnUiThread { if (th.tag == v.id) th.setImageBitmap(bm) } }
        }
        row.addView(th)
      }
      val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(if (v != null) 12 else 0), 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
      }
      col.addView(TextView(ctx).apply { text = r.title; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
      col.addView(TextView(ctx).apply { text = r.sub; textSize = 12f; setTextColor(0xFF999999.toInt()) })
      row.addView(col)
      row.setOnClickListener { r.click() }
      return row
    }
  }

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.INVISIBLE }
    val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
    val et = EditText(this).apply { hint = "Search videos"; setSingleLine(); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
    et.addTextChangedListener(object : TextWatcher {
      override fun afterTextChanged(s: Editable?) { q = s.toString().trim(); show() }
      override fun beforeTextChanged(a: CharSequence?, x: Int, y: Int, z: Int) {}
      override fun onTextChanged(a: CharSequence?, x: Int, y: Int, z: Int) {}
    })
    sortBtn = TextView(this).apply {
      text = "Sort: Date"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(12), dp(8), dp(4), dp(8))
      setOnClickListener { sort = (sort + 1) % 4; text = "Sort: ${sortNames[sort]}"; show() }
    }
    bar.addView(et); bar.addView(sortBtn)
    lv = ListView(this)
    box.addView(bar); box.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
    setContentView(box)
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
    asking = true; box.visibility = View.INVISIBLE; auth.launch(i)
  }
  private fun unlock() {
    unlocked = true; box.visibility = View.VISIBLE
    if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) scan() else requestPermissions(arrayOf(perm), 1)
  }
  override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
    super.onRequestPermissionsResult(c, p, r)
    if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) scan()
  }
  private fun scan() {
    all.clear()
    val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val cols = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
      MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED)
    contentResolver.query(base, cols, null, null, null)?.use { c ->
      while (c.moveToNext()) {
        val id = c.getLong(0)
        all += V(id, c.getString(1) ?: "?", ContentUris.withAppendedId(base, id).toString(), c.getString(2) ?: "Other", c.getLong(3), c.getLong(4), c.getLong(5))
      }
    }
    show()
  }
  private fun sorted(l: List<V>): List<V> = when (sort) {
    1 -> l.sortedBy { it.name.lowercase() }
    2 -> l.sortedByDescending { it.size }
    3 -> l.sortedByDescending { it.dur }
    else -> l.sortedByDescending { it.date }
  }
  private fun vrows(l: List<V>) = l.mapIndexed { i, v -> Row(v.name, "${fmt(v.dur)}  -  ${mb(v.size)}", v) { play(l, i) } }
  private fun play(l: List<V>, i: Int) {
    skip = true
    startActivity(Intent(this, PlayerActivity::class.java)
      .putStringArrayListExtra("uris", ArrayList(l.map { it.uri })).putExtra("i", i)
      .putStringArrayListExtra("names", ArrayList(l.map { it.name })))
  }
  private fun show() {
    val f = cur
    rows = if (q.isNotEmpty()) vrows(sorted(all.filter { it.name.contains(q, true) }))
    else if (f == null) {
      val g = all.groupBy { it.folder }
      g.keys.sortedBy { it.lowercase() }.map { k -> Row("📁 $k", "${g[k]!!.size} videos", null) { cur = k; show() } }
    } else vrows(sorted(all.filter { it.folder == f }))
    if (lv.adapter == null) lv.adapter = adapter else adapter.notifyDataSetChanged()
    lv.setSelection(0)
  }
  @Deprecated("") override fun onBackPressed() { if (cur != null && q.isEmpty()) { cur = null; show() } else super.onBackPressed() }
}
