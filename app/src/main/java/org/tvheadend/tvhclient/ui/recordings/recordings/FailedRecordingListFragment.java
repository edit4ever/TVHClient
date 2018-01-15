package org.tvheadend.tvhclient.ui.recordings.recordings;

import android.os.Bundle;

import org.tvheadend.tvhclient.R;
import org.tvheadend.tvhclient.TVHClientApplication;
import org.tvheadend.tvhclient.data.DataStorage;
import org.tvheadend.tvhclient.data.model.Recording;
import org.tvheadend.tvhclient.service.HTSListener;

import java.util.Map;

public class FailedRecordingListFragment extends RecordingListFragment implements HTSListener {

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        toolbarInterface.setTitle(getString(R.string.failed_recordings));
    }

    @Override
    public void onResume() {
        super.onResume();
        TVHClientApplication.getInstance().addListener(this);

        if (!DataStorage.getInstance().isLoading()) {
            populateList();
            // In dual-pane mode the list of programs of the selected
            // channel will be shown additionally in the details view
            if (isDualPane && adapter.getCount() > 0) {
                showRecordingDetails(selectedListPosition);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        TVHClientApplication.getInstance().removeListener(this);
    }

    @Override
    protected void populateList() {
        // Clear the list and add the recordings
        adapter.clear();
        Map<Integer, Recording> map = DataStorage.getInstance().getRecordingsFromArray();
        for (Recording recording : map.values()) {
            if (recording.isFailed() || recording.isAborted() || recording.isMissed()) {
                adapter.add(recording);
            }
        }
        super.populateList();
    }

    @Override
    public void onMessage(String action, final Object obj) {
        switch (action) {
            case "dvrEntryAdd":
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Recording recording = (Recording) obj;
                        if (recording.isFailed() || recording.isAborted() || recording.isMissed()) {
                            adapter.add(recording);
                            adapter.notifyDataSetChanged();
                            toolbarInterface.setSubtitle(getResources().getQuantityString(R.plurals.recordings, adapter.getCount(), adapter.getCount()));
                        }
                    }
                });
                break;
            case "dvrEntryUpdate":
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Recording recording = (Recording) obj;
                        if (recording.isFailed() || recording.isAborted() || recording.isMissed()) {
                            adapter.update(recording);
                            adapter.notifyDataSetChanged();
                        }
                    }
                });
                break;
            case "dvrEntryDelete":
                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        // Get the position of the recording that is to be
                        // deleted so the previous one can be selected
                        if (--selectedListPosition < 0) {
                            selectedListPosition = 0;
                        }
                        Recording recording = (Recording) obj;
                        adapter.remove(recording);
                        adapter.notifyDataSetChanged();
                        // Update the number of recordings
                        toolbarInterface.setSubtitle(getResources().getQuantityString(R.plurals.recordings, adapter.getCount(), adapter.getCount()));
                        // Select the previous recording to show its details
                        if (isDualPane) {
                            showRecordingDetails(selectedListPosition);
                        }
                    }
                });
                break;
        }
    }
}