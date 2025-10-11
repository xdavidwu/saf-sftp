package link.xdavidwu.saf.sftp;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.util.Log;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.file.FileSystems;
import java.security.PublicKey;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class MainActivity extends PreferenceActivity implements OnSharedPreferenceChangeListener {
	private EditTextPreference hostText, portText, usernameText, passwdText, remotePathText;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.main_pre);

		hostText = (EditTextPreference) findPreference("host");
		portText = (EditTextPreference) findPreference("port");
		usernameText = (EditTextPreference) findPreference("username");
		passwdText = (EditTextPreference) findPreference("passwd");
		remotePathText = (EditTextPreference) findPreference("mountpoint");
		Preference testConnection = findPreference("test_connection");
		testConnection.setOnPreferenceClickListener((p) -> {
			SharedPreferences settings = MainActivity.this.
				getPreferenceScreen().getSharedPreferences();
			ProgressDialog pd = ProgressDialog.show(MainActivity.this,
				"Connection test", "Connecting.");
			CompletableFuture.supplyAsync(() -> {
				try {
					var params = SftpConnectionParameters.fromSharedPreferences(settings);
					var session = params.preAuth();
					session.setServerKeyVerifier(new SshServerKeyVerifier(MainActivity.this));
					session.auth().verify();
					session.close();
					return "Succeeded.";
				} catch (IOException e) {
					return e.getMessage();
				}
			}).thenAccept(message -> MainActivity.this.runOnUiThread(() -> {
				new AlertDialog.Builder(MainActivity.this)
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
			var settings = MainActivity.this.
				getPreferenceScreen().getSharedPreferences();
			var params = SftpConnectionParameters.fromSharedPreferences(settings);
			var intent = new Intent(Intent.ACTION_VIEW, params.getRootContentUri());
			startActivity(intent);
			return true;
		});

		SharedPreferences settings = getPreferenceScreen().getSharedPreferences();
		settings.registerOnSharedPreferenceChangeListener(this);
		if (!settings.getString("host", "").equals(""))
			hostText.setSummary(settings.getString("host", ""));
		if (!settings.getString("port", "").equals(""))
			portText.setSummary(settings.getString("port", ""));
		if (!settings.getString("username", "").equals(""))
			usernameText.setSummary(settings.getString("username", ""));
		if (!settings.getString("passwd", "").equals(""))
			passwdText.setSummary(getString(R.string.passwd_filled));
		if (!settings.getString("mountpoint", "").equals(""))
			remotePathText.setSummary(settings.getString("mountpoint", ""));
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences settings, String key) {
		var params = SftpConnectionParameters.fromSharedPreferences(settings);
		getContentResolver().notifyChange(params.getRootContentUri(), null);
		switch (key) {
			case "host":
				if (settings.getString("host", "").equals(""))
					hostText.setSummary(getString(R.string.host_summary));
				else
					hostText.setSummary(settings.getString("host", ""));
				break;
			case "port":
				if (settings.getString("port", "").equals(""))
					portText.setSummary(getString(R.string.port_summary));
				else
					portText.setSummary(settings.getString("port", ""));
				break;
			case "username":
				if (settings.getString("username", "").equals(""))
					usernameText.setSummary(
						getString(R.string.username_summary));
				else
					usernameText.setSummary(settings.getString("username", ""));
				break;
			case "passwd":
				if (settings.getString("passwd", "").equals(""))
					passwdText.setSummary(getString(R.string.passwd_summary));
				else
					passwdText.setSummary(getString(R.string.passwd_filled));
				break;
			case "mountpoint":
				if (settings.getString("mountpoint", "").equals(""))
					remotePathText.setSummary(
						getString(R.string.remote_path_summary));
				else
					remotePathText.setSummary(
						settings.getString("mountpoint", ""));
				break;
		}
	}
}
