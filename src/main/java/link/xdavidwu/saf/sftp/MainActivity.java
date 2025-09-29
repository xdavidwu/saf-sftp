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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.file.FileSystems;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.io.PathUtils;

public class MainActivity extends PreferenceActivity implements OnSharedPreferenceChangeListener {

	private static SshClient ssh;
	static {
		PathUtils.setUserHomeFolderResolver(() -> FileSystems.getDefault().getPath("/"));
		ssh = SshClient.setUpDefaultClient();
		ssh.start();
	}
	private EditTextPreference hostText, portText, usernameText, passwdText, remotePathText;

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
		remotePathText = (EditTextPreference) findPreference("mountpoint");
		Preference testConnection = findPreference("test_connection");
		testConnection.setOnPreferenceClickListener((p) -> {
			SharedPreferences settings = MainActivity.this.
				getPreferenceScreen().getSharedPreferences();
			ProgressDialog pd = ProgressDialog.show(MainActivity.this,
				"Connection test", "Connecting.");
			AsyncTask.execute(() -> {
				String result = "Succeeded.";
				try {
					var session = ssh.connect(
						settings.getString("username", ""),
						settings.getString("host", ""),
						Integer.parseInt(settings.getString("port", "22"))
					).verify(Duration.ofSeconds(3)).getClientSession();
					session.addPasswordIdentity(settings.getString("passwd", ""));
					session.setServerKeyVerifier((ClientSession s, SocketAddress a, PublicKey k) -> {
						var serverHostKey = k.getEncoded();
						CompletableFuture<Boolean> acceptFuture =
							new CompletableFuture<>();

						var raw = new ByteArrayBuffer();
						raw.putRawPublicKey(k);
						var key64 = new SpannableString(
							Base64.getEncoder().encodeToString(
								Arrays.copyOfRange(raw.array(), 0, raw.wpos())));
						var md5 = new SpannableString(
							KeyUtils.getFingerPrint(BuiltinDigests.md5, k));
						var sha256 = new SpannableString(
							KeyUtils.getFingerPrint(BuiltinDigests.sha256, k));

						key64.setSpan(new TypefaceSpan("monospace"), 0, key64.length(),
							Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
						md5.setSpan(new TypefaceSpan("monospace"), 0, md5.length(),
							Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
						sha256.setSpan(new TypefaceSpan("monospace"), 0,
							sha256.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

						MainActivity.this.runOnUiThread(() -> {
							new AlertDialog.Builder(MainActivity.this)
								.setTitle("Host key verification")
								.setMessage(new SpannableStringBuilder(
										"Accept SSH server key of type " +
										KeyUtils.getKeyType(k) + ": ")
									.append(key64).append("?\n")
									.append(md5).append("\n")
									.append(sha256))
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
					});
					session.auth().verify();
					session.close();
				} catch (IOException e) {
					result = e.getMessage();
				}
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
			remotePathText.setSummary(settings.getString("mountpoint", ""));
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
					remotePathText.setSummary(
						getString(R.string.remote_path_summary));
				else
					remotePathText.setSummary(
						settings.getString("mountpoint", ""));
				break;
		}
	}
}
