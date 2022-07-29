package org.safsftp;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.provider.DocumentsContract;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StrictMode;
import android.util.Log;

import com.trilead.ssh2.crypto.Base64;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ServerHostKeyVerifier;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class MainActivity extends PreferenceActivity implements OnSharedPreferenceChangeListener {

	private class DialogKeyVerifier implements ServerHostKeyVerifier {
		public boolean verifyServerHostKey(String hostname, int port,
				String serverHostKeyAlgorithm,
				byte[] serverHostKey) {
			Log.e("SFTP", "verify");
			CompletableFuture<Boolean> acceptFuture =
				new CompletableFuture<>();
			final String key64 = new String(Base64.encode(serverHostKey));
			MainActivity.this.runOnUiThread(() -> {
				new AlertDialog.Builder(MainActivity.this)
					.setTitle("Host key verification")
					.setMessage("Accept SSH server key of " +
						serverHostKeyAlgorithm + " " +
						key64 + "?")
					.setCancelable(false)
					.setPositiveButton("Accept", (dialog, which) -> {
						acceptFuture.complete(true);
						Log.e("SFTP", "y");
					}).setNegativeButton("Deny", (dialog, which) -> {
						acceptFuture.complete(false);
					})
					.show();
			});
			try {
				return acceptFuture.get();
			} catch (Exception e) {
				Log.e("SFTP", "verify: " + e.getMessage());
				return false;
			}
		}
	}

	private EditTextPreference hostText, portText, usernameText, passwdText, mountpointText;

	private void notifyRootChanges() {
		Uri uri = DocumentsContract.buildRootsUri("org.safsftp");
		getContentResolver().notifyChange(uri, null);
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.main_pre);

		hostText = (EditTextPreference) findPreference("host");
		portText = (EditTextPreference) findPreference("port");
		usernameText = (EditTextPreference) findPreference("username");
		passwdText = (EditTextPreference) findPreference("passwd");
		mountpointText = (EditTextPreference) findPreference("mountpoint");
		Preference testConnection = findPreference("test_connection");
		testConnection.setOnPreferenceClickListener((p) -> {
			SharedPreferences settings = MainActivity.this.
				getPreferenceScreen().getSharedPreferences();
			Connection connection = new Connection(
				settings.getString("host", ""),
				Integer.parseInt(settings.getString("port", "22")));
			AsyncTask.execute(() -> {
				String result = "Succeed.";
				try {
					connection.connect(new DialogKeyVerifier(), 10000, 10000);
					if (!connection.authenticateWithPassword(
							settings.getString("username", ""),
							settings.getString("passwd", ""))) {
						result = "Authentication failed.";
						connection.close();
					}
				} catch (IOException e) {
					result = e.getMessage();
				}
				connection.close();
				final String message = result;
				MainActivity.this.runOnUiThread(() -> {
					new AlertDialog.Builder(MainActivity.this)
						.setTitle("Connection test result")
						.setMessage(message)
						.setPositiveButton("OK", null)
						.show();
				});
			});
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
			mountpointText.setSummary(settings.getString("mountpoint", ""));
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences settings, String key) {
		notifyRootChanges();
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
					mountpointText.setSummary(
						getString(R.string.mountpoint_summary));
				else
					mountpointText.setSummary(
						settings.getString("mountpoint", ""));
				break;
		}
	}
}
