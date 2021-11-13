package com.yogeshpaliyal.keypass.ui.backup

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yogeshpaliyal.common.AppDatabase
import com.yogeshpaliyal.common.utils.*
import com.yogeshpaliyal.keypass.R
import com.yogeshpaliyal.keypass.databinding.BackupActivityBinding
import com.yogeshpaliyal.keypass.databinding.LayoutBackupKeypharseBinding
import com.yogeshpaliyal.keypass.utils.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@AndroidEntryPoint
class BackupActivity : AppCompatActivity() {

    companion object {
        @JvmStatic
        fun start(context: Context?) {
            val starter = Intent(context, BackupActivity::class.java)
            context?.startActivity(starter)
        }
    }

    private lateinit var binding: BackupActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = BackupActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    @AndroidEntryPoint
    class SettingsFragment : PreferenceFragmentCompat() {

        @Inject
        lateinit var sp: SharedPreferences

        @Inject
        lateinit var appDb: com.yogeshpaliyal.common.AppDatabase

        private val CHOOSE_BACKUPS_LOCATION_REQUEST_CODE = 26212

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.backup_preferences, rootKey)
            updateItems()
        }

        override fun onPreferenceTreeClick(preference: Preference?): Boolean {
            when (preference?.key) {
                getString(R.string.settings_start_backup) -> {
                    startBackup()
                }
                getString(R.string.settings_create_backup) -> {
                    context?.let {
                        if (it.canUserAccessBackupDirectory(sp)) {
                            val selectedDirectory = Uri.parse(getBackupDirectory(sp))
                            backup(selectedDirectory)
                        }
                    }
                }
                getString(R.string.settings_backup_folder) -> {
                    changeBackupFolder()
                }
                getString(R.string.settings_verify_key_phrase) -> {
                    verifyKeyPhrase()
                }
                getString(R.string.settings_stop_backup) -> {
                    stopBackup()
                }
                getString(R.string.settings_auto_backup) -> {
                    sp.apply {
                        setAutoBackupEnabled(isAutoBackupEnabled().not())
                    }
                    updateItems()
                }
                getString(R.string.settings_override_auto_backup) -> {
                    sp.apply {
                        setOverrideAutoBackup(overrideAutoBackup().not())
                    }
                    updateItems()
                }
            }
            return super.onPreferenceTreeClick(preference)
        }

        fun backup(selectedDirectory: Uri) {
            lifecycleScope.launch {
                context.backupAccounts(sp, appDb, selectedDirectory)?.let { keyPair ->
                    if (keyPair.first) {
                        val binding = LayoutBackupKeypharseBinding.inflate(layoutInflater)
                        binding.txtCode.text = getOrCreateBackupKey(sp).second
                        binding.txtCode.setOnClickListener {
                            val clipboard =
                                ContextCompat.getSystemService(
                                    requireContext(),
                                    ClipboardManager::class.java
                                )
                            val clip = ClipData.newPlainText("KeyPass", binding.txtCode.text)
                            clipboard?.setPrimaryClip(clip)
                            Toast.makeText(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                        }
                        MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
                            .setPositiveButton(
                                getString(R.string.yes)
                            ) { dialog, which ->
                                dialog?.dismiss()
                            }.show()
                    } else {
                        Toast.makeText(context, getString(R.string.backup_completed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            updateItems()
        }

        private fun updateItems() {
            val isBackupEnabled = context.canUserAccessBackupDirectory(sp)

            val isAutoBackupEnabled = sp.isAutoBackupEnabled()

            findPreference<Preference>(getString(R.string.settings_start_backup))?.isVisible = isBackupEnabled.not()
            findPreference<Preference>(getString(R.string.settings_stop_backup))?.isVisible = isBackupEnabled

            findPreference<Preference>(getString(R.string.settings_auto_backup))?.isVisible = isBackupEnabled
            findPreference<Preference>(getString(R.string.settings_auto_backup))?.summary = if (isAutoBackupEnabled) getString(R.string.enabled) else getString(R.string.disabled)

            findPreference<PreferenceCategory>(getString(R.string.settings_cat_auto_backup))?.isVisible = isBackupEnabled && isAutoBackupEnabled

            findPreference<Preference>(getString(R.string.settings_override_auto_backup))?.summary = if (sp.overrideAutoBackup()) getString(R.string.enabled) else getString(R.string.disabled)

            findPreference<Preference>(getString(R.string.settings_create_backup))?.isVisible = isBackupEnabled
            findPreference<Preference>(getString(R.string.settings_create_backup))?.summary = getString(R.string.last_backup_date, getBackupTime(sp).formatCalendar("dd MMM yyyy hh:mm aa"))
            findPreference<Preference>(getString(R.string.settings_backup_folder))?.isVisible = isBackupEnabled
            val directory = URLDecoder.decode(getBackupDirectory(sp), "utf-8").split("/")
            val folderName = directory.get(directory.lastIndex)
            findPreference<Preference>(getString(R.string.settings_backup_folder))?.summary = folderName
            findPreference<Preference>(getString(R.string.settings_verify_key_phrase))?.isVisible = false
            findPreference<Preference>(getString(R.string.settings_backup))?.isVisible = isBackupEnabled
        }

        private fun startBackup() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

            intent.addFlags(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            try {
                startActivityForResult(intent, CHOOSE_BACKUPS_LOCATION_REQUEST_CODE)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == CHOOSE_BACKUPS_LOCATION_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
                val contentResolver = context?.contentResolver
                val selectedDirectory = data?.data
                if (contentResolver != null && selectedDirectory != null) {
                    contentResolver.takePersistableUriPermission(
                        selectedDirectory,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )

                    setBackupDirectory(sp, selectedDirectory.toString())
                    backup(selectedDirectory)
                }
            }
        }

        private fun changeBackupFolder() {
            startBackup()
        }

        private fun verifyKeyPhrase() {
            Toast.makeText(context, getString(R.string.coming_soon), Toast.LENGTH_SHORT).show()
        }

        private fun stopBackup() {
            clearBackupKey(sp)
            setBackupDirectory(sp, "")
            setBackupTime(sp, -1)
            sp.apply {
                setOverrideAutoBackup(false)
                setAutoBackupEnabled(false)
            }
            updateItems()
        }
    }
}
