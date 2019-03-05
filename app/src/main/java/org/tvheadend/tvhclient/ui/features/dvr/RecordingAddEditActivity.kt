package org.tvheadend.tvhclient.ui.features.dvr

import android.os.Bundle
import androidx.fragment.app.Fragment
import org.tvheadend.tvhclient.R
import org.tvheadend.tvhclient.ui.base.BaseActivity
import org.tvheadend.tvhclient.ui.base.callbacks.BackPressedInterface
import org.tvheadend.tvhclient.ui.features.dvr.recordings.RecordingAddEditFragment
import org.tvheadend.tvhclient.ui.features.dvr.series_recordings.SeriesRecordingAddEditFragment
import org.tvheadend.tvhclient.ui.features.dvr.timer_recordings.TimerRecordingAddEditFragment
import org.tvheadend.tvhclient.util.MiscUtils

// TODO split into 3 activities

class RecordingAddEditActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(MiscUtils.getThemeId(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.misc_content_activity)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            var fragment: Fragment? = null
            val type = intent.getStringExtra("type")
            when (type) {
                "recording" -> fragment = RecordingAddEditFragment()
                "series_recording" -> fragment = SeriesRecordingAddEditFragment()
                "timer_recording" -> fragment = TimerRecordingAddEditFragment()
            }

            if (fragment != null) {
                fragment.arguments = intent.extras
                supportFragmentManager.beginTransaction().add(R.id.main, fragment).commit()
            }
        }
    }

    override fun onBackPressed() {
        val fragment = supportFragmentManager.findFragmentById(R.id.main)
        if (fragment is BackPressedInterface) {
            fragment.onBackPressed()
        } else {
            super.onBackPressed()
        }
    }
}