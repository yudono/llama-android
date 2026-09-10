package com.llamacpp.local

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.io.File

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val db = ChatDb(requireContext())
            db.migratePrefsOnce(requireContext())
            preferenceManager.preferenceDataStore = DbDataStore(db)
            setPreferencesFromResource(R.xml.prefs, rootKey)
            refreshDirSummary()
            findPreference<Preference>("models_dir")?.setOnPreferenceClickListener {
                pickFolder.launch(null)
                true
            }
        }

        private val pickFolder = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val path = treeUriToPath(uri)
            if (path == null) {
                Toast.makeText(requireContext(), "Folder tak dikenali", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val dir = File(path)
            if (!dir.exists() && !dir.mkdirs() || !dir.canWrite()) {
                Toast.makeText(
                    requireContext(),
                    "Folder tak bisa ditulis — beri izin akses file dulu", Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
            ChatDb(requireContext()).set("models_dir", path)
            refreshDirSummary()
            Toast.makeText(requireContext(), "Folder model: $path", Toast.LENGTH_SHORT).show()
        }

        // treeUri "primary:models" -> /storage/emulated/0/models (termasuk SD card).
        private fun treeUriToPath(uri: Uri): String? {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: return null
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null
            val base = if (parts[0] == "primary") Environment.getExternalStorageDirectory().absolutePath
                       else "/storage/${parts[0]}"
            return if (parts[1].isEmpty()) base else "$base/${parts[1]}"
        }

        private fun refreshDirSummary() {
            findPreference<Preference>("models_dir")?.summary =
                ChatDb(requireContext()).get("models_dir", ModelDownloader.DEFAULT_DIR)
        }
    }
}
