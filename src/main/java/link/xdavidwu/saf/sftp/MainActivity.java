package link.xdavidwu.saf.sftp;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StrictMode;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.provider.DocumentsContract;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.TypefaceSpan;
import android.util.Log;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ServerHostKeyVerifier;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public class MainActivity extends PreferenceActivity implements OnSharedPreferenceChangeListener {

	private class DialogKeyVerifier implements ServerHostKeyVerifier {
		public boolean verifyServerHostKey(String hostname, int port,
				String serverHostKeyAlgorithm,
				byte[] serverHostKey) {
			CompletableFuture<Boolean> acceptFuture =
				new CompletableFuture<>();

			byte[] md5, sha256;
			try {
				md5 = MessageDigest.getInstance("MD5").digest(serverHostKey);
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException("MD5 not available");
			}
			try {
				sha256 = MessageDigest.getInstance("SHA-256").digest(serverHostKey);
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException("SHA-256 not available");
			}

			final SpannableString key64 = new SpannableString(
				Base64.getEncoder().encodeToString(serverHostKey));
			final SpannableString md5Str = new SpannableString(String.format(
				"MD5:%02x:%02x:%02x:%02x:%02x:%02x:%02x:%02x:" +
				"%02x:%02x:%02x:%02x:%02x:%02x:%02x:%02x", md5[0], md5[1],
				md5[2], md5[3], md5[4], md5[5], md5[6], md5[7], md5[8], md5[9],
				md5[10], md5[11], md5[12], md5[13], md5[14], md5[15]));
			final SpannableString sha256Str = new SpannableString("SHA-256:" +
				Base64.getEncoder().withoutPadding().encodeToString(sha256));

			key64.setSpan(new TypefaceSpan("monospace"), 0, key64.length(),
				Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			md5Str.setSpan(new TypefaceSpan("monospace"), 0, md5Str.length(),
				Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			sha256Str.setSpan(new TypefaceSpan("monospace"), 0,
				sha256Str.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

			MainActivity.this.runOnUiThread(() -> {
				new AlertDialog.Builder(MainActivity.this)
					.setTitle("Host key verification")
					.setMessage(new SpannableStringBuilder(
							"Accept SSH server key of type " +
							serverHostKeyAlgorithm + ": ")
						.append(key64).append("?\n")
						.append(md5Str).append("\n")
						.append(sha256Str))
					.setCancelable(false)
					.setPositiveButton("Accept", (dialog, which) -> {
						acceptFuture.complete(true);
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
		Uri uri = DocumentsContract.buildRootsUri("link.xdavidwu.saf.sftp");
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
			ProgressDialog pd = ProgressDialog.show(MainActivity.this,
				"Connection test", "Connecting.");
			AsyncTask.execute(() -> {
				String result = "Succeeded.";
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
					pd.dismiss();
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
