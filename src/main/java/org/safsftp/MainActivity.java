package org.safsftp;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.preference.EditTextPreference;
import android.preference.PreferenceActivity;
import android.os.Bundle;

public class MainActivity extends PreferenceActivity
	implements OnSharedPreferenceChangeListener {

	private EditTextPreference hostText, portText, usernameText, passwdText;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.main_pre);

		hostText=(EditTextPreference)findPreference("host");
		portText=(EditTextPreference)findPreference("port");
		usernameText=(EditTextPreference)findPreference("username");
		passwdText=(EditTextPreference)findPreference("passwd");

		getPreferenceScreen().getSharedPreferences()
			.registerOnSharedPreferenceChangeListener(this);
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences settings,
			String key) {
		switch(key){
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
				usernameText.setSummary(getString(R.string.username_summary));
			else
				usernameText.setSummary(settings.getString("username", ""));
			break;
		case "passwd":
			if (settings.getString("passwd", "").equals(""))
				passwdText.setSummary(getString(R.string.passwd_summary));
			else
				passwdText.setSummary(getString(R.string.passwd_filled));
			break;
		}
	}
}
