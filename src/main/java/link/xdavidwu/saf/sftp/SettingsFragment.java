package link.xdavidwu.saf.sftp;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SettingsFragment extends PreferenceFragment
		implements OnSharedPreferenceChangeListener {
	private static final Map<String, Integer> EMPTY_SUMMARY_MAPPING = new HashMap<>();
	static {
		EMPTY_SUMMARY_MAPPING.put("host", R.string.host_summary);
		EMPTY_SUMMARY_MAPPING.put("port", R.string.port_summary);
		EMPTY_SUMMARY_MAPPING.put("username", R.string.username_summary);
		EMPTY_SUMMARY_MAPPING.put("mountpoint", R.string.remote_path_summary);
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.main_pre);

		Preference testConnection = findPreference("test_connection");
		var activity = getActivity();
		testConnection.setOnPreferenceClickListener((p) -> {
			SharedPreferences settings = SettingsFragment.this.
				getPreferenceScreen().getSharedPreferences();
			ProgressDialog pd = ProgressDialog.show(activity,
				"Connection test", "Connecting.");
			CompletableFuture.supplyAsync(() -> {
				try {
					var params = SftpConnectionParameters.fromSharedPreferences(settings);
					var session = params.preAuth();
					session.setServerKeyVerifier(
						new SshServerKeyVerifier(activity));
					session.auth().verify();
					session.close();
					return "Succeeded.";
				} catch (IOException e) {
					return e.getMessage();
				}
			}).thenAccept(message -> activity.runOnUiThread(() -> {
				new AlertDialog.Builder(activity)
					.setTitle("Connection test result")
					.setMessage(message)
					.setPositiveButton("OK", null)
					.show();
				pd.dismiss();
			}));
			return true;
		});
		var launch = findPreference("launch");
		launch.setOnPreferenceClickListener(p -> {
			var settings = SettingsFragment.this.
				getPreferenceScreen().getSharedPreferences();
			var params = SftpConnectionParameters.fromSharedPreferences(settings);
			var intent = new Intent(Intent.ACTION_VIEW, params.getRootContentUri());
			startActivity(intent);
			return true;
		});

		SharedPreferences settings = getPreferenceScreen().getSharedPreferences();
		settings.registerOnSharedPreferenceChangeListener(this);
		EMPTY_SUMMARY_MAPPING.forEach((key, resId) -> {
			var val = settings.getString(key, "");
			if (!"".equals(val)) {
				findPreference(key).setSummary(val);
			}
		});
		if (!settings.getString("passwd", "").equals("")) {
			findPreference("passwd").setSummary(
				getString(R.string.passwd_filled));
		}
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences settings, String key) {
		var params = SftpConnectionParameters.fromSharedPreferences(settings);
		getActivity().getContentResolver().notifyChange(params.getRootContentUri(), null);
		var resIdBoxed = EMPTY_SUMMARY_MAPPING.get(key);
		if (resIdBoxed != null) {
			var val = settings.getString(key, "");
			findPreference(key).setSummary(
				"".equals(val) ? getString(resIdBoxed) : val);
		} else if ("passwd".equals(key)) {
			var val = settings.getString(key, "");
			findPreference(key).setSummary(getString("".equals(val) ?
					R.string.passwd_summary : R.string.passwd_filled));
		}
	}
}
