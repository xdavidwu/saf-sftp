package link.xdavidwu.saf.sftp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

public class SftpIOService extends Service {
	@Override
	public IBinder onBind(Intent intent) {
		var nm = getSystemService(NotificationManager.class);
		nm.createNotificationChannel(new NotificationChannel(
			"FOREGROUND", "File IO running notices",
			NotificationManager.IMPORTANCE_NONE));
		// XXX not really shown (we don't even have POST_NOTIFICATIONS?)
		// but it does become FGS
		startForeground(42,
			new Notification.Builder(this, "FOREGROUND")
				.setContentText("Processing SFTP file IO...").build());
		return new Binder();
	}
}
