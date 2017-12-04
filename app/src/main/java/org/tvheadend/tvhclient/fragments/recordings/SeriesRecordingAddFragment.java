package org.tvheadend.tvhclient.fragments.recordings;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnKeyListener;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v7.widget.Toolbar;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.sleepbot.datetimepicker.time.RadialPickerLayout;
import com.sleepbot.datetimepicker.time.TimePickerDialog;

import org.tvheadend.tvhclient.Constants;
import org.tvheadend.tvhclient.DataStorage;
import org.tvheadend.tvhclient.DatabaseHelper;
import org.tvheadend.tvhclient.R;
import org.tvheadend.tvhclient.TVHClientApplication;
import org.tvheadend.tvhclient.htsp.HTSService;
import org.tvheadend.tvhclient.interfaces.FragmentStatusInterface;
import org.tvheadend.tvhclient.interfaces.HTSListener;
import org.tvheadend.tvhclient.model.Channel;
import org.tvheadend.tvhclient.model.Channel2;
import org.tvheadend.tvhclient.model.Connection;
import org.tvheadend.tvhclient.model.Profile;
import org.tvheadend.tvhclient.model.SeriesRecording2;
import org.tvheadend.tvhclient.utils.Utils;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Locale;

public class SeriesRecordingAddFragment extends DialogFragment implements HTSListener {

    private final static String TAG = SeriesRecordingAddFragment.class.getSimpleName();

    private Activity activity;
    private SeriesRecording2 rec;
    private Toolbar toolbar;

    private CheckBox isEnabled;
    private TextView priority;
    private EditText minDuration;
    private EditText maxDuration;
    private final ToggleButton[] daysOfWeekButtons = new ToggleButton[7];
    private TextView startTime;
    private CheckBox timeEnabled;
    private TextView startWindowTime;
    private EditText startExtraTime;
    private EditText stopExtraTime;
    private TextView dupDetect;
    private TextView dupDetectLabel;
    private EditText directory;
    private TextView directoryLabel;
    private EditText title;
    private EditText name;
    private TextView channelName;
    private TextView dvrConfigName;
    private TextView dvrConfigNameLabel;

    private long priorityValue;
    private long minDurationValue;
    private long maxDurationValue;
    private long startTimeValue;
    private long startWindowTimeValue;
    private boolean timeEnabledValue;
    private long startExtraTimeValue;
    private long stopExtraTimeValue;
    private long dupDetectValue;
    private long daysOfWeekValue;
    private String directoryValue;
    private String titleValue;
    private String nameValue;
    private boolean enabledValue;
    private int channelSelectionValue;
    private int dvrConfigNameValue;

    private String[] channelList;
    private String[] priorityList;
    private String[] dvrConfigList;
    private String[] dupDetectList;

    private TVHClientApplication app;

    // Determines if an entry shall be added to the channel selection list to
    // allow recording on all channels
    private boolean allowRecordingOnAllChannels = false;

    private DatabaseHelper databaseHelper;

    private static final int DEFAULT_START_EXTRA = 2;
    private static final int DEFAULT_STOP_EXTRA = 2;
    private DataStorage dataStorage;
    private Context mContext;

    public static SeriesRecordingAddFragment newInstance() {
        SeriesRecordingAddFragment f = new SeriesRecordingAddFragment();
        return f;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        if (dialog.getWindow() != null) {
            dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }
        return dialog;
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().getAttributes().windowAnimations = R.style.dialog_animation_fade;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        getValues();
        outState.putLong("priorityValue", priorityValue);
        outState.putLong("minDurationValue", minDurationValue);
        outState.putLong("maxDurationValue", maxDurationValue);
        outState.putLong("startTimeValue", startTimeValue);
        outState.putLong("startWindowTimeValue", startWindowTimeValue);
        outState.putBoolean("timeEnabled", timeEnabledValue);
        outState.putLong("startExtraTimeValue", startExtraTimeValue);
        outState.putLong("stopExtraTimeValue", stopExtraTimeValue);
        outState.putLong("dupDetectValue", dupDetectValue);
        outState.putLong("daysOfWeekValue", daysOfWeekValue);
        outState.putString("directoryValue", directoryValue);
        outState.putString("titleValue", titleValue);
        outState.putString("nameValue", nameValue);
        outState.putBoolean("enabledValue", enabledValue);
        outState.putInt("channelNameValue", channelSelectionValue);
        outState.putInt("configNameValue", dvrConfigNameValue);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        // Initialize all the widgets from the layout
        View v = inflater.inflate(R.layout.series_recording_add_layout, container, false);
        channelName = (TextView) v.findViewById(R.id.channel);
        isEnabled = (CheckBox) v.findViewById(R.id.is_enabled);
        directoryLabel = (TextView) v.findViewById(R.id.directory_label);
        directory = (EditText) v.findViewById(R.id.directory);
        title = (EditText) v.findViewById(R.id.title);
        name = (EditText) v.findViewById(R.id.name);
        minDuration = (EditText) v.findViewById(R.id.minimum_duration);
        maxDuration = (EditText) v.findViewById(R.id.maximum_duration);

        // For the shown days in each toggle button the array with the short
        // names is used. If the screen width is not large enough then the short
        // names of all seven days would not fit. Therefore reduce the number of
        // shown letters for each day depending on the screen width.
        DisplayMetrics displaymetrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(displaymetrics);
        final int displayWidth = displaymetrics.widthPixels;

        LinearLayout daysOfWeekLayout = (LinearLayout) v.findViewById(R.id.days_of_week_layout);
        String[] shortDays = getResources().getStringArray(R.array.day_short_names);
        for (int i = 0; i < 7; i++) {
            final ToggleButton dayButton = (ToggleButton) inflater.inflate(R.layout.day_toggle_button, daysOfWeekLayout, false);

            // Show only one character on width below 800, two characters below
            // 1000 and all characters on all remaining ones
            if (displayWidth < 800) {
                dayButton.setTextOn(shortDays[i].subSequence(0, 1));
                dayButton.setTextOff(shortDays[i].subSequence(0, 1));
            } else if (displayWidth < 1000) {
                dayButton.setTextOn(shortDays[i].subSequence(0, 2));
                dayButton.setTextOff(shortDays[i].subSequence(0, 2));
            } else {
                dayButton.setTextOn(shortDays[i]);
                dayButton.setTextOff(shortDays[i]);
            }

            // Add the button to the layout and store it in the list to have
            // access to it later 
            daysOfWeekLayout.addView(dayButton);
            daysOfWeekButtons[i] = dayButton;
        }

        startTime = (TextView) v.findViewById(R.id.start_after_time);
        timeEnabled = (CheckBox) v.findViewById(R.id.time_enabled);
        startWindowTime = (TextView) v.findViewById(R.id.start_before_time);
        startExtraTime = (EditText) v.findViewById(R.id.start_extra);
        stopExtraTime = (EditText) v.findViewById(R.id.stop_extra);
        dupDetect = (TextView) v.findViewById(R.id.duplicate_detection);
        dupDetectLabel = (TextView) v.findViewById(R.id.duplicate_detection_label);
        priority = (TextView) v.findViewById(R.id.priority);
        dvrConfigName = (TextView) v.findViewById(R.id.dvr_config);
        dvrConfigNameLabel = (TextView) v.findViewById(R.id.dvr_config_label);
        toolbar = (Toolbar) v.findViewById(R.id.toolbar);
        return v;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        activity = getActivity();
        app = TVHClientApplication.getInstance();
        databaseHelper = DatabaseHelper.getInstance(getActivity().getApplicationContext());
        dataStorage = DataStorage.getInstance();

        // Determine if the server supports recording on all channels
        allowRecordingOnAllChannels = dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_ALL_CHANNELS;
        final int offset = (allowRecordingOnAllChannels ? 1 : 0);

        // Create the list of channels that the user can select. If recording on
        // all channels are available the add the 'all channels' string to
        // the beginning of the list before adding the available channels.
        channelList = new String[dataStorage.getChannels().size() + offset];
        if (allowRecordingOnAllChannels) {
            channelList[0] = activity.getString(R.string.all_channels);
        }
        for (int i = 0; i < dataStorage.getChannels().size(); i++) {
            channelList[i + offset] = dataStorage.getChannels().get(i).name;
        }

        // Sort the channels in the list by name. Keep the all channels string
        // always in the first position
        Arrays.sort(channelList, new Comparator<String>() {
            public int compare(String x, String y) {
                if (x != null && y != null) {
                    if (y.equals(activity.getString(R.string.no_channel))) {
                        return 1;
                    } else if (y.equals(activity.getString(R.string.all_channels))) {
                        return 1;
                    } else {
                        return x.toLowerCase(Locale.US).compareTo(
                                y.toLowerCase(Locale.US));
                    }
                }
                return 0;
            }
        });

        // Create the list of available configurations that the user can select from
        dvrConfigList = new String[dataStorage.getDvrConfigs().size()];
        for (int i = 0; i < dataStorage.getDvrConfigs().size(); i++) {
            dvrConfigList[i] = dataStorage.getDvrConfigs().get(i).name;
        }

        priorityList = activity.getResources().getStringArray(R.array.dvr_priorities);

        dupDetectList = activity.getResources().getStringArray(R.array.duplicate_detection_list);

        // If the savedInstanceState is null then the fragment was created for
        // the first time. Either get the given id to edit the recording or
        // create new one. Otherwise an orientation change has occurred and the
        // saved values must be applied to the user input elements.
        if (savedInstanceState == null) {
            String recId = "";
            Bundle bundle = getArguments();
            if (bundle != null) {
                recId = bundle.getString("id");
            }

            // Get the recording so we can show its details
            rec = dataStorage.getSeriesRecordingFromArray(recId);
            if (rec != null) {
                priorityValue = rec.priority;
                minDurationValue = (rec.minDuration / 60);
                maxDurationValue = (rec.maxDuration / 60);
                timeEnabledValue = (rec.start >= 0 || rec.startWindow >= 0);
                startTimeValue = rec.start;
                startWindowTimeValue = rec.startWindow;
                startExtraTimeValue = rec.startExtra;
                stopExtraTimeValue = rec.stopExtra;
                dupDetectValue = rec.dupDetect;
                daysOfWeekValue = rec.daysOfWeek;
                directoryValue = rec.directory;
                titleValue = rec.title;
                nameValue = rec.name;
                enabledValue = rec.enabled > 0;

                // The default value is no channel
                channelSelectionValue = 0;
                // Get the position of the given channel in the channelList
                Channel2 channel = dataStorage.getChannelFromArray(rec.channel);
                if (channel != null) {
                    for (int i = 0; i < channelList.length; i++) {
                        if (channelList[i].equals(channel.channelName)) {
                            channelSelectionValue = i;
                            break;
                        }
                    }
                } else {
                    // If no channel is set preselect either all
                    // channels or the first channel available
                    if (allowRecordingOnAllChannels) {
                        channelSelectionValue = 0;
                    } else {
                        channelSelectionValue = 1;
                    }
                }
            } else {
                // No recording was given, set default values
                Calendar cal = Calendar.getInstance();
                priorityValue = 2;
                minDurationValue = 0;
                maxDurationValue = 0;
                timeEnabledValue = true;
                startTimeValue = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
                startWindowTimeValue = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
                startExtraTimeValue = DEFAULT_START_EXTRA;
                stopExtraTimeValue = DEFAULT_STOP_EXTRA;
                dupDetectValue = 0;
                daysOfWeekValue = 127;
                directoryValue = "";
                titleValue = "";
                nameValue = "";
                enabledValue = true;
                channelSelectionValue = 0;
            }

            // Get the position of the selected profile in the dvrConfigList
            dvrConfigNameValue = 0;
            final Connection conn = databaseHelper.getSelectedConnection();
            final Profile p = databaseHelper.getProfile(conn.recording_profile_id);
            if (p != null) {
                for (int i = 0; i < dvrConfigList.length; i++) {
                    if (dvrConfigList[i].equals(p.name)) {
                        dvrConfigNameValue = i;
                        break;
                    }
                }
            }

        } else {
            // Restore the values before the orientation change
            priorityValue = savedInstanceState.getLong("priorityValue");
            minDurationValue = savedInstanceState.getLong("minDurationValue");
            maxDurationValue = savedInstanceState.getLong("maxDurationValue");
            timeEnabledValue = savedInstanceState.getBoolean("timeEnabledValue");
            startTimeValue = savedInstanceState.getLong("startTimeValue");
            startWindowTimeValue = savedInstanceState.getLong("startWindowTimeValue");
            startExtraTimeValue = savedInstanceState.getLong("startExtraTimeValue");
            stopExtraTimeValue = savedInstanceState.getLong("stopExtraTimeValue");
            dupDetectValue = savedInstanceState.getLong("dupDetectValue");
            daysOfWeekValue = savedInstanceState.getLong("daysOfWeekValue");
            directoryValue = savedInstanceState.getString("directoryValue");
            titleValue = savedInstanceState.getString("titleValue");
            nameValue = savedInstanceState.getString("nameValue");
            enabledValue = savedInstanceState.getBoolean("enabledValue");
            channelSelectionValue = savedInstanceState.getInt("channelNameValue");
            dvrConfigNameValue = savedInstanceState.getInt("configNameValue");
        }

        isEnabled.setChecked(enabledValue);
        isEnabled.setVisibility(dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_ENABLED ? View.VISIBLE : View.GONE);

        directoryLabel.setVisibility(dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_DIRECTORY ? View.VISIBLE : View.GONE);
        directory.setVisibility(dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_DIRECTORY ? View.VISIBLE : View.GONE);
        directory.setText(directoryValue);

        title.setText(titleValue);
        name.setText(nameValue);

        channelName.setText(channelList[channelSelectionValue]);
        channelName.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View view) {
				new MaterialDialog.Builder(activity)
	            .title(R.string.select_channel)
	            .items(channelList)
	            .itemsCallbackSingleChoice(channelSelectionValue, new MaterialDialog.ListCallbackSingleChoice() {
	                @Override
	                public boolean onSelection(MaterialDialog dialog, View view, int which, CharSequence text) {
	                    channelName.setText(channelList[which]);
	                    channelSelectionValue = which;
	                    return true;
	                }
	            })
	            .show();
			}
        });

        // TODO check why array index out of bound exception occurred
        // Somehow the value was the same as the list size and
        // therefore has triggered an array index out of bound exception

        if (priorityList.length > priorityValue) {
            priority.setText(priorityList[(int) priorityValue]);
        }

        priority.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View view) {
				new MaterialDialog.Builder(activity)
	            .title(R.string.select_priority)
	            .items(priorityList)
	            .itemsCallbackSingleChoice((int) priorityValue, new MaterialDialog.ListCallbackSingleChoice() {
	                @Override
	                public boolean onSelection(MaterialDialog dialog, View view, int which, CharSequence text) {
                        priority.setText(priorityList[which]);
                        priorityValue = which;
	                    return true;
	                }
	            })
	            .show();
			}
        });

        if (dvrConfigName != null && dvrConfigNameLabel != null) {
            if ((rec != null && rec.id.length() > 0) || dvrConfigList.length == 0) {
                dvrConfigName.setText("");
                dvrConfigName.setVisibility(View.GONE);
                dvrConfigNameLabel.setVisibility(View.GONE);
            } else {
                dvrConfigName.setVisibility(View.VISIBLE);
                dvrConfigNameLabel.setVisibility(View.VISIBLE);
                dvrConfigName.setText(dvrConfigList[dvrConfigNameValue]);
                dvrConfigName.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        new MaterialDialog.Builder(activity)
                        .title(R.string.select_dvr_config)
                        .items(dvrConfigList)
                        .itemsCallbackSingleChoice(dvrConfigNameValue, new MaterialDialog.ListCallbackSingleChoice() {
                            @Override
                            public boolean onSelection(MaterialDialog dialog, View view, int which, CharSequence text) {
                                dvrConfigName.setText(dvrConfigList[which]);
                                dvrConfigNameValue = which;
                                return true;
                            }
                        })
                        .show();
                    }
                });
            }
        }

        minDuration.setText(minDurationValue > 0 ? String.valueOf(minDurationValue) : getString(R.string.duration_sum));
        maxDuration.setText(maxDurationValue > 0 ? String.valueOf(maxDurationValue) : getString(R.string.duration_sum));

        startTime.setText(Utils.getTimeStringFromValue(activity, startTimeValue));
        // Show the time picker dialog so the user can select a new starting time
        startTime.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {

                // If the time is not set avoid showing a -1 in the time picker.
                // Use the current time 
                if (startTimeValue < 0) {
                    Calendar cal = Calendar.getInstance();
                    startTimeValue = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
                }

                // Convert the time to the hour and minutes
                int hour = (int) (startTimeValue / 60);
                int minute = (int) (startTimeValue % 60);

                TimePickerDialog mTimePicker;
                mTimePicker = TimePickerDialog.newInstance(new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(RadialPickerLayout timePicker, int selectedHour, int selectedMinute) {
                        // Save the given value in seconds. This values will be passed to the server
                        startTimeValue = (long) (selectedHour * 60 + selectedMinute);
                        startTime.setText(Utils.getTimeStringFromValue(activity, startTimeValue));
                    }
                }, hour, minute, true, false);

                mTimePicker.setCloseOnSingleTapMinute(false);
                mTimePicker.show(getChildFragmentManager(), "");
            }
        });

        startWindowTime.setText(Utils.getTimeStringFromValue(activity, startWindowTimeValue));
        // Show the time picker dialog so the user can select a new starting time
        startWindowTime.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {

                // If the time is not set avoid showing a -1 in the time picker.
                // Use the current time 
                if (startWindowTimeValue < 0) {
                    Calendar cal = Calendar.getInstance();
                    startWindowTimeValue = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
                }

                // Convert the time to the hour and minutes
                int hour = (int) (startWindowTimeValue / 60);
                int minute = (int) (startWindowTimeValue % 60);

                TimePickerDialog mTimePicker;
                mTimePicker = TimePickerDialog.newInstance(new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(RadialPickerLayout timePicker, int selectedHour, int selectedMinute) {
                        // Save the given value in seconds. This values will be passed to the server
                        startWindowTimeValue = (long) (selectedHour * 60 + selectedMinute);
                        startWindowTime.setText(Utils.getTimeStringFromValue(activity, startWindowTimeValue));
                    }
                }, hour, minute, true, false);

                mTimePicker.setCloseOnSingleTapMinute(false);
                mTimePicker.show(getChildFragmentManager(), "");
            }
        });

        timeEnabled.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean checked = timeEnabled.isChecked();
                startTime.setEnabled(checked);
                startTime.setText(Utils.getTimeStringFromValue(activity, (checked ? startTimeValue : -1)));
                startWindowTime.setEnabled(checked);
                startWindowTime.setText(Utils.getTimeStringFromValue(activity, (checked ? startWindowTimeValue : -1)));
            }
        });

        timeEnabled.setChecked(timeEnabledValue);

        startExtraTime.setText(String.valueOf(startExtraTimeValue));
        stopExtraTime.setText(String.valueOf(stopExtraTimeValue));

        dupDetectLabel.setVisibility(dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_DUPDETECT ? View.VISIBLE : View.GONE);
        dupDetect.setVisibility(dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_DUPDETECT ? View.VISIBLE : View.GONE);

        dupDetect.setText(dupDetectList[(int) dupDetectValue]);
        dupDetect.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View view) {
				new MaterialDialog.Builder(activity)
	            .title(R.string.select_duplicate_detection)
	            .items(dupDetectList)
	            .itemsCallbackSingleChoice((int) dupDetectValue, new MaterialDialog.ListCallbackSingleChoice() {
	                @Override
	                public boolean onSelection(MaterialDialog dialog, View view, int which, CharSequence text) {
                        dupDetect.setText(dupDetectList[which]);
                        dupDetectValue = which;
	                    return true;
	                }
	            })
	            .show();
			}
        });

        // Set the correct days as checked or not depending on the given value.
        // For each day shift the daysOfWeekValue by one to the right and check
        // if the bit at this position is one. 
        for (int i = 0; i < 7; i++) {
            int checked = (((int) daysOfWeekValue >> i) & 1);
            daysOfWeekButtons[i].setChecked(checked == 1);
        }

        if (toolbar != null) {
            toolbar.setTitle(rec != null ? R.string.edit_series_recording : R.string.add_series_recording);
            toolbar.inflateMenu(R.menu.save_cancel_menu);
            toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    return onToolbarItemSelected(item);
                }
            });
        }
        if (getDialog() != null) {
            getDialog().setCanceledOnTouchOutside(false);
        }
    }

	@Override
	public void onResume() {
		super.onResume();
		getDialog().setOnKeyListener(new OnKeyListener() {
			@Override
			public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
				if ((keyCode == android.view.KeyEvent.KEYCODE_BACK)) {
					getDialog().setOnKeyListener(null);
					cancel();
				}
				return false;
			}
		});
		app.addListener(this);
	}

    @Override
    public void onPause() {
        super.onPause();
        app.removeListener(this);
    }

    /**
     * Called when the user has selected a menu item in the toolbar
     *
     * @param item Selected menu item
     * @return True if selection was handled, otherwise false
     */
    private boolean onToolbarItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.menu_save:
            save();
            return true;

        case R.id.menu_cancel:
            cancel();
            return true;
        }
        return false;
    }

    /**
     * Retrieves and checks the values from the user input elements and stores
     * them in internal variables. These are used to remember the values during
     * an orientation change or when the recording shall be saved.
     */
    private void getValues() {
        try {
            minDurationValue = Long.valueOf(minDuration.getText().toString());
        } catch(NumberFormatException ex) {
            minDurationValue = 0;
        }
        try {
            maxDurationValue = Long.valueOf(maxDuration.getText().toString());
        } catch(NumberFormatException ex) {
            maxDurationValue = 0;
        }

        timeEnabledValue = timeEnabled.isChecked();

        try {
            startExtraTimeValue = Long.valueOf(startExtraTime.getText().toString());
        } catch(NumberFormatException ex) {
            startExtraTimeValue = 0;
        }
        try {
            stopExtraTimeValue = Long.valueOf(stopExtraTime.getText().toString());
        } catch(NumberFormatException ex) {
            stopExtraTimeValue = 0;
        }

        directoryValue = directory.getText().toString();
        titleValue = title.getText().toString();
        nameValue = name.getText().toString();
        enabledValue = isEnabled.isChecked();
        daysOfWeekValue = getDayOfWeekValue();
    }

    /**
     * Checks certain given values for plausibility and if everything is fine
     * creates the intent that will be passed to the service to save the newly
     * created recording.
     */
    private void save() {
        getValues();

        // The title must not be empty
        if (titleValue.length() == 0) {
            Toast.makeText(activity, getString(R.string.error_empty_title),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // The maximum duration must be at least the minimum duration
        if (minDurationValue > 0 && maxDurationValue > 0 && maxDurationValue < minDurationValue) {
            maxDurationValue = minDurationValue;
        }

        // Update the timer recording if it has been edited, otherwise add a new one.
        if (rec != null && rec.id != null && rec.id.length() > 0) {

            if (dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_UPDATE_SERIES_RECORDINGS) {
                // If the API version supports it, use the native service call method
                updateSeriesRecording();
            } else {
                // Remove the recording before adding it again with the updated values. 
                // This is required because the API does not provide an edit service call. 
                // When the removal confirmation was received, add the edited recording. 
                // This is done in the onMessage method. 
                Intent intent = new Intent(activity, HTSService.class);
                intent.setAction("deleteAutorecEntry");
                intent.putExtra("id", rec.id);
                activity.startService(intent);
            }
        } else {
            addSeriesRecording();
        }
    }

    /**
     * Asks the user to confirm canceling the current activity. If no is
     * chosen the user can continue to add or edit the recording. Otherwise
     * the input will be discarded and the activity will be closed.
     */
    private void cancel() {
        new MaterialDialog.Builder(activity)
                .content(R.string.cancel_add_recording)
                .positiveText(getString(R.string.discard))
                .negativeText(getString(R.string.cancel))
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        if (getDialog() != null) {
                            getDialog().dismiss();
                        }
                    }
                })
                .onNegative(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        dialog.cancel();
                    }
                })
                .show();
    }

    /**
     * Returns a number where each bit position is one day. If the bit position
     * is one then the day was selected.
     * 
     * @return Number with the selected day on each bit position
     */
    private long getDayOfWeekValue() {
        long value = 0;
        for (int i = 0; i < 7; i++) {
            if (daysOfWeekButtons[i].isChecked()) {
                value += (1 << i);
            }
        }
        return value;
    }

    @Override
    public void onMessage(String action, Object obj) {
        if (action.equals("autorecEntryDelete")) {
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    addSeriesRecording();
                }
            });
        }
    }

    /**
     * Adds a new series recording with the given values. This method is also
     * called when a recording is being edited. It adds a recording with edited
     * values which was previously removed.
     */
    private void addSeriesRecording() {
        Intent intent = getIntentData();
        intent.setAction("addAutorecEntry");
        activity.startService(intent);

        if (getDialog() != null) {
            ((FragmentStatusInterface) activity).listDataInvalid(TAG);
            getDialog().dismiss();
        }
    }
    
    /**
     * Update the series recording with the given values.
     */
    private void updateSeriesRecording() {
        Intent intent = getIntentData();
        intent.setAction("updateAutorecEntry");
        intent.putExtra("id", rec.id);
        activity.startService(intent);

        if (getDialog() != null) {
            ((FragmentStatusInterface) activity).listDataInvalid(TAG);
            getDialog().dismiss();
        }
    }

    /**
     * Returns an intent with the recording data 
     */
    private Intent getIntentData() {
        Intent intent = new Intent(activity, HTSService.class);
        intent.putExtra("title", titleValue);
        intent.putExtra("name", nameValue);
        intent.putExtra("directory", directoryValue);
        intent.putExtra("minDuration", minDurationValue * 60);
        intent.putExtra("maxDuration", maxDurationValue * 60);

        // Assume no start time is specified if 0:00 is selected
        if (timeEnabledValue) {
            intent.putExtra("start", startTimeValue);
            intent.putExtra("startWindow", startWindowTimeValue);
        }

        intent.putExtra("startExtra", startExtraTimeValue);
        intent.putExtra("stopExtra", stopExtraTimeValue);
        intent.putExtra("dupDetect", dupDetectValue);
        intent.putExtra("daysOfWeek", daysOfWeekValue);
        intent.putExtra("priority", priorityValue);
        intent.putExtra("enabled", (long) (enabledValue ? 1 : 0));

        // If the all channels recording is not enabled or a valid channel name
        // was selected get the channel id that needs to be passed to the
        // server. So go through all available channels and get the id for the
        // selected channel name.
        if (!allowRecordingOnAllChannels || channelSelectionValue > 1) {
            for (Channel c : dataStorage.getChannels()) {
                if (c.name.equals(channelName.getText().toString())) {
                    intent.putExtra("channelId", c.id);
                    break;
                }
            }
        }

        // Add the recording profile if available and enabled
        final Connection conn = databaseHelper.getSelectedConnection();
        final Profile p = databaseHelper.getProfile(conn.recording_profile_id);
        if (p != null 
                && p.enabled
                && (dvrConfigName.getText().length() > 0)
                && dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_PROFILES
                && app.isUnlocked()) {
            // Use the selected profile. If no change was done in the 
            // selection then the default one from the connection setting will be used
            intent.putExtra("configName", dvrConfigName.getText().toString());
        }
        return intent;
    }
}
