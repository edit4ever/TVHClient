package org.tvheadend.tvhclient.features.shared.models;

import android.app.Application;
import android.arch.lifecycle.AndroidViewModel;
import android.arch.lifecycle.LiveData;
import android.content.SharedPreferences;

import org.tvheadend.tvhclient.MainApplication;
import org.tvheadend.tvhclient.R;
import org.tvheadend.tvhclient.data.entity.ChannelTag;
import org.tvheadend.tvhclient.data.entity.Recording;
import org.tvheadend.tvhclient.data.entity.ServerStatus;
import org.tvheadend.tvhclient.data.repository.AppRepository;

import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import timber.log.Timber;

public class BaseChannelViewModel extends AndroidViewModel {

    private final String allChannelsSelectedText;
    private final String multipleChannelTagsSelectedText;
    private final String unknownChannelTagText;
    @Inject
    protected AppRepository appRepository;
    @Inject
    protected SharedPreferences sharedPreferences;

    protected long selectedTime;
    protected Set<Integer> channelTagIds;
    private int channelSortOrder;
    private boolean showGenreColors;

    public BaseChannelViewModel(Application application) {
        super(application);
        MainApplication.getComponent().inject(this);

        allChannelsSelectedText = application.getString(R.string.all_channels);
        multipleChannelTagsSelectedText = application.getString(R.string.multiple_channel_tags);
        unknownChannelTagText = application.getString(R.string.unknown);

        channelTagIds = appRepository.getChannelTagData().getSelectedChannelTagIds();
        selectedTime = new Date().getTime();
        channelSortOrder = Integer.valueOf(sharedPreferences.getString("channel_sort_order", "0"));
        showGenreColors = sharedPreferences.getBoolean("genre_colors_for_channels_enabled", false);
    }

    public LiveData<List<Recording>> getAllRecordings() {
        return appRepository.getRecordingData().getLiveDataItems();
    }

    public LiveData<ServerStatus> getServerStatus() {
        return appRepository.getServerStatusData().getLiveDataActiveItem();
    }

    public String getSelectedChannelTagName() {
        if (channelTagIds.size() == 1) {
            ChannelTag channelTag = appRepository.getChannelTagData().getItemById(channelTagIds.iterator().next());
            if (channelTag != null) {
                return channelTag.getTagName();
            } else {
                return unknownChannelTagText;
            }
        } else if (channelTagIds.size() == 0) {
            return allChannelsSelectedText;
        } else {
            return multipleChannelTagsSelectedText;
        }
    }

    long getSelectedTime() {
        return selectedTime;
    }

    protected void setSelectedTime(long selectedTime) {
        this.selectedTime = selectedTime;
    }

    public Set<Integer> getChannelTagIds() {
        return channelTagIds;
    }

    public void setChannelTagIds(Set<Integer> channelTagIds) {
        this.channelTagIds = channelTagIds;

        List<ChannelTag> channelTags = appRepository.getChannelTagData().getItems();
        for (ChannelTag channelTag : channelTags) {
            channelTag.setIsSelected(0);
            if (channelTagIds.contains(channelTag.getTagId())) {
                channelTag.setIsSelected(1);
            }
            appRepository.getChannelTagData().updateItem(channelTag);
        }
    }

    public boolean isUpdateOfChannelsRequired() {
        Timber.d("Checking if channels need to be updated");
        boolean updateChannels = false;

        Set<Integer> newChannelTagIds = appRepository.getChannelTagData().getSelectedChannelTagIds();
        if (channelTagIds != newChannelTagIds) {
            channelTagIds = newChannelTagIds;
            updateChannels = true;
        }
        int newChannelSortOrder = Integer.valueOf(sharedPreferences.getString("channel_sort_order", "0"));
        if (channelSortOrder != newChannelSortOrder) {
            Timber.d("Sort order has changed from " + channelSortOrder + " to " + newChannelSortOrder);
            channelSortOrder = newChannelSortOrder;
            updateChannels = true;
        }
        boolean newShowGenreColors = sharedPreferences.getBoolean("genre_colors_for_channels_enabled", false);
        if (showGenreColors != newShowGenreColors) {
            Timber.d("Channel genre color has changed from " + showGenreColors + " to " + newShowGenreColors);
            showGenreColors = newShowGenreColors;
            updateChannels = true;
        }
        Timber.d("Done checking if channels need to be updated");
        return updateChannels;
    }
}
